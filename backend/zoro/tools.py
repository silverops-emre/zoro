"""Tools the model can call, and the per-request Turn they act on.

Server tools change the SQLite store directly. Phone tools only append an
action to the response; the Android app executes it. Destructive tools never
act directly: they open a pending confirmation that the next user turn must
answer with yes/no.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any, Callable
from zoneinfo import ZoneInfo

from . import info
from .config import Config
from .store import Store
from .timeutil import DAILY_MS, human_time, iso, next_occurrence_daily, parse_iso, to_millis

# Capabilities a client can declare. The v1 app sends none.
CAP_CALENDAR = "calendar"
CAP_NAVIGATE = "navigate"
CAP_CALL = "call"
CAP_MESSAGE = "message"
CAP_OPEN_APP = "open_app"
CAP_ALARM = "alarm"
CAP_REMINDERS = "reminders"
ALL_CAPS = {CAP_CALENDAR, CAP_NAVIGATE, CAP_CALL, CAP_MESSAGE, CAP_OPEN_APP, CAP_ALARM, CAP_REMINDERS}

CAP_LABELS = {
    CAP_CALENDAR: "takvim okuma/yazma",
    CAP_NAVIGATE: "navigasyon (Google Haritalar)",
    CAP_CALL: "telefon araması",
    CAP_MESSAGE: "SMS/WhatsApp mesaj taslağı",
    CAP_OPEN_APP: "uygulama açma (YouTube, Spotify, WhatsApp...)",
    CAP_ALARM: "saat uygulamasında alarm ve zamanlayıcı",
}

OPEN_APPS = ["youtube", "spotify", "whatsapp", "maps", "camera", "phone", "messages",
             "calendar", "settings", "browser", "clock"]


@dataclass
class ClientInfo:
    version: int = 1
    capabilities: set[str] = field(default_factory=set)

    def can(self, cap: str) -> bool:
        return cap in self.capabilities


@dataclass
class CalendarEvent:
    ref: str
    id: str
    title: str
    start: datetime
    end: datetime
    location: str
    source: str
    all_day: bool

    @property
    def editable(self) -> bool:
        return self.source.upper() == "ZORO"


@dataclass
class Turn:
    cfg: Config
    store: Store
    tz: ZoneInfo
    now: datetime
    client: ClientInfo
    calendar: list[CalendarEvent] | None = None  # None = client did not send any
    actions: list[dict[str, Any]] = field(default_factory=list)
    legacy: dict[str, Any] | None = None
    notes: list[str] = field(default_factory=list)
    pending: dict[str, Any] | None = None  # open confirmation (from DB)
    pending_created: dict[str, Any] | None = None
    tools_called: list[str] = field(default_factory=list)

    def set_legacy(self, fields: dict[str, Any]) -> None:
        # The v1 app understands a single action per response: keep the first.
        if self.legacy is None:
            self.legacy = fields

    def event(self, ref: str) -> CalendarEvent | None:
        ref = (ref or "").strip()
        for ev in self.calendar or []:
            if ev.ref == ref or ev.id == ref:
                return ev
        return None


def ok(**data: Any) -> dict[str, Any]:
    return {"ok": True, **data}


def fail(error: str, **data: Any) -> dict[str, Any]:
    return {"ok": False, "error": error, **data}


# ----------------------------------------------------------------- helpers

def task_ref(task: dict[str, Any]) -> str:
    return "t:" + task["id"][:6]


def resolve_task(turn: Turn, ref: str) -> tuple[dict[str, Any] | None, str | None]:
    raw = (ref or "").strip()
    if raw.startswith("t:"):
        raw = raw[2:]
    if not raw:
        return None, "Görev kimliği boş."
    matches = turn.store.find_tasks_by_prefix(raw)
    if len(matches) == 1:
        return matches[0], None
    if not matches:
        return None, f"'{ref}' kimlikli görev yok. Bağlamdaki [t:...] kimliklerinden birini kullan."
    return None, "Kimlik birden fazla göreve uyuyor, daha uzun kimlik ver."


def task_when(turn: Turn, task: dict[str, Any]) -> str:
    due = parse_iso(task.get("due_at"), turn.tz)
    if due is None:
        return "zamansız"
    if task.get("repeat_interval") == DAILY_MS:
        return f"her gün {due.strftime('%H:%M')}"
    return human_time(due, turn.now)


def reminder_action(task: dict[str, Any]) -> dict[str, Any]:
    return {
        "type": "schedule_reminder",
        "alarm_id": task["id"],
        "text": task["text"],
        "due_at": task["due_at"],
        "repeat_interval": task.get("repeat_interval", 0),
    }


def cancel_action(task: dict[str, Any]) -> dict[str, Any]:
    return {"type": "cancel_reminder", "alarm_id": task["id"], "due_at": task.get("due_at"), "text": task["text"]}


def legacy_add(task: dict[str, Any]) -> dict[str, Any]:
    return {
        "action": "add_task",
        "task_text": task["text"],
        "due_at": task.get("due_at"),
        "alarm_id": task["id"],
        "repeat_interval": task.get("repeat_interval", 0),
    }


def legacy_cancel(kind: str, task: dict[str, Any]) -> dict[str, Any]:
    return {
        "action": kind,
        "task_text": task["text"],
        "alarm_id": task["id"],
        "due_at": task.get("due_at"),
        "cancel_alarm_ids": [task["id"]],
        "cancel_due_ats": [task["due_at"]] if task.get("due_at") else [],
    }


def parse_future(turn: Turn, value: Any, what: str, grace_s: int = 60) -> tuple[datetime | None, str | None]:
    dt = parse_iso(value, turn.tz)
    if dt is None:
        return None, f"{what} geçersiz. Saat dilimli ISO-8601 ver, örn. {iso(turn.now.replace(microsecond=0) + timedelta(hours=1))}."
    if dt < turn.now - timedelta(seconds=grace_s):
        return None, f"{what} geçmişte kalıyor ({human_time(dt, turn.now)}). Şu an {human_time(turn.now, turn.now)}."
    if dt > turn.now + timedelta(days=3 * 365):
        return None, f"{what} çok ileri bir tarih."
    return dt, None


def clean_text(value: Any, limit: int = 300) -> str:
    return " ".join(str(value or "").split())[:limit]


# ------------------------------------------------------------ server tools

def t_add_task(turn: Turn, text: str, due_at: str | None, repeat: str) -> dict[str, Any]:
    text = clean_text(text, 500)
    if not text:
        return fail("Hatırlatma metni boş.")
    due_iso = None
    interval = DAILY_MS if repeat == "daily" else 0
    if due_at:
        first = parse_iso(due_at, turn.tz)
        if interval and first is not None and first < turn.now:
            # A daily reminder whose time today has passed starts tomorrow.
            due_at = iso(next_occurrence_daily(first, turn.now))
        due, error = parse_future(turn, due_at, "Hatırlatma zamanı")
        if error:
            return fail(error)
        due_iso = iso(due)  # type: ignore[arg-type]
    elif interval:
        return fail("Günlük tekrar için saat gerekli.")
    task = turn.store.add_task(text, due_iso, interval)
    if due_iso:
        turn.actions.append(reminder_action(task))
        turn.set_legacy(legacy_add(task))
    turn.notes.append(f"{text} hatırlatması {task_when(turn, task)} için kuruldu" if due_iso else f"{text} görevi kaydedildi")
    return ok(id=task_ref(task), zaman=task_when(turn, task), alarm_kuruldu=bool(due_iso))


def t_complete_task(turn: Turn, task_id: str) -> dict[str, Any]:
    task, error = resolve_task(turn, task_id)
    if error:
        return fail(error)
    if task["done"]:  # type: ignore[index]
        return ok(zaten_tamamlanmis=True)
    task = turn.store.update_task(task["id"], done=True, notified=True)  # type: ignore[index]
    turn.actions.append(cancel_action(task))  # type: ignore[arg-type]
    turn.set_legacy(legacy_cancel("complete_task", task))  # type: ignore[arg-type]
    turn.notes.append(f"{task['text']} tamamlandı")  # type: ignore[index]
    return ok(tamamlandi=task["text"])  # type: ignore[index]


def recent_reminder(turn: Turn) -> list[dict[str, Any]]:
    """Tasks whose alarm fired in the last 30 minutes (v1 logic)."""
    found = []
    for task in turn.store.list_tasks(pending_only=True):
        due = parse_iso(task.get("due_at"), turn.tz)
        if due is None:
            continue
        if task.get("repeat_interval") == DAILY_MS:
            due = due.replace(year=turn.now.year, month=turn.now.month, day=turn.now.day)
            if due > turn.now:
                due -= timedelta(days=1)
        age = (turn.now - due).total_seconds()
        if 0 <= age <= 1800:
            found.append((age, task))
    found.sort(key=lambda x: x[0])
    if not found:
        return []
    nearest = found[0][0]
    return [task for age, task in found if age - nearest < 120]


def t_snooze_task(turn: Turn, task_id: str | None, minutes: int) -> dict[str, Any]:
    if not 1 <= int(minutes) <= 1440:
        return fail("Erteleme 1 ile 1440 dakika arasında olmalı.")
    if task_id:
        task, error = resolve_task(turn, task_id)
        if error:
            return fail(error)
    else:
        candidates = recent_reminder(turn)
        if len(candidates) != 1:
            return fail("Hangi hatırlatmanın erteleneceği belli değil; kullanıcıya hangisi olduğunu sor.")
        task = candidates[0]
    if task["done"]:  # type: ignore[index]
        return fail("Tamamlanmış görev ertelenemez.")
    due = turn.now + timedelta(minutes=int(minutes))
    # Daily master task stays; snooze is a separate one-shot task (v1 behaviour).
    new = turn.store.add_task(task["text"], iso(due), 0)  # type: ignore[index]
    turn.actions.append(reminder_action(new))
    turn.set_legacy(legacy_add(new))
    turn.notes.append(f"{new['text']} {int(minutes)} dakika ertelendi")
    return ok(yeni_zaman=human_time(due, turn.now), id=task_ref(new))


def t_edit_task(turn: Turn, task_id: str, text: str | None, due_at: str | None) -> dict[str, Any]:
    task, error = resolve_task(turn, task_id)
    if error:
        return fail(error)
    fields: dict[str, Any] = {}
    if text and clean_text(text, 500):
        fields["text"] = clean_text(text, 500)
    if due_at:
        due, error = parse_future(turn, due_at, "Yeni zaman")
        if error:
            return fail(error)
        fields["due_at"] = iso(due)  # type: ignore[arg-type]
        fields["notified"] = False
    if not fields:
        return fail("Değişecek bir şey verilmedi.")
    updated = turn.store.update_task(task["id"], **fields)  # type: ignore[index]
    if updated and updated.get("due_at") and not updated["done"]:
        # Same alarm_id: the phone replaces the existing alarm.
        turn.actions.append(cancel_action(task))  # type: ignore[arg-type]
        turn.actions.append(reminder_action(updated))
        turn.set_legacy(legacy_add(updated))
    turn.notes.append(f"{updated['text']} güncellendi ({task_when(turn, updated)})")  # type: ignore[index]
    return ok(id=task_ref(updated), metin=updated["text"], zaman=task_when(turn, updated))  # type: ignore[index]


def t_delete_task(turn: Turn, task_id: str) -> dict[str, Any]:
    task, error = resolve_task(turn, task_id)
    if error:
        return fail(error)
    question = f"{task['text']} ({task_when(turn, task)}) hatırlatmasını sileyim mi?"  # type: ignore[index]
    return request_confirmation(turn, "delete_task", {"task_id": task["id"]}, question)  # type: ignore[index]


def t_clear_tasks(turn: Turn) -> dict[str, Any]:
    count = len(turn.store.list_tasks())
    if not count:
        return fail("Kayıtlı görev yok.")
    return request_confirmation(turn, "clear_tasks", {}, f"Kayıtlı {count} görevin hepsini sileyim mi?")


def t_remember(turn: Turn, text: str, key: str | None) -> dict[str, Any]:
    text = clean_text(text, 1000)
    if not text:
        return fail("Kaydedilecek bilgi boş.")
    fact, created = turn.store.upsert_fact(text, key=key or None)
    turn.notes.append("Bilgi hafızaya kaydedildi")
    return ok(id="f:" + fact["id"][:6], yeni=created)


def resolve_fact(turn: Turn, ref: str) -> tuple[dict[str, Any] | None, str | None]:
    raw = (ref or "").strip().removeprefix("f:")
    matches = [f for f in turn.store.list_facts() if raw and f["id"].startswith(raw)]
    if len(matches) == 1:
        return matches[0], None
    return None, "Hafıza kaydı bulunamadı; bağlamdaki [f:...] kimliğini kullan."


def t_forget(turn: Turn, fact_id: str) -> dict[str, Any]:
    fact, error = resolve_fact(turn, fact_id)
    if error:
        return fail(error)
    return request_confirmation(turn, "forget_fact", {"fact_id": fact["id"]},  # type: ignore[index]
                                f"\"{fact['text']}\" bilgisini hafızamdan sileyim mi?")  # type: ignore[index]


def t_get_weather(turn: Turn, city: str | None) -> dict[str, Any]:
    try:
        return info.weather(clean_text(city, 80) or turn.cfg.home_city, turn.cfg.timezone)
    except Exception as error:  # network, parsing
        return fail(f"Hava durumu servisine ulaşılamadı ({type(error).__name__}).")


def t_get_exchange_rates(turn: Turn, codes: list[str]) -> dict[str, Any]:
    try:
        return info.exchange_rates([c for c in codes if isinstance(c, str)][:6] or None)
    except Exception as error:
        return fail(f"Kur servisine ulaşılamadı ({type(error).__name__}).")


def t_confirm_pending(turn: Turn, answer: str) -> dict[str, Any]:
    if not turn.pending:
        return fail("Onay bekleyen işlem yok.")
    if answer == "yes":
        reply = execute_pending(turn, turn.pending)
        return ok(sonuc=reply)
    turn.store.close_pending(turn.pending["id"], "cancelled")
    turn.notes.append("Bekleyen işlem iptal edildi")
    return ok(sonuc="İşlem iptal edildi, hiçbir şey silinmedi.")


# ------------------------------------------------------------- phone tools

def overlapping(turn: Turn, start: datetime, end: datetime, exclude_id: str | None = None) -> list[CalendarEvent]:
    return [ev for ev in turn.calendar or []
            if not ev.all_day and ev.id != exclude_id and ev.start < end and ev.end > start]


def describe_event(turn: Turn, ev: CalendarEvent) -> str:
    return f"{ev.title} ({human_time(ev.start, turn.now)})"


def t_calendar_add(turn: Turn, title: str, start: str, end: str | None, location: str | None, notes: str | None) -> dict[str, Any]:
    title = clean_text(title, 120)
    if not title:
        return fail("Etkinlik başlığı boş.")
    start_dt, error = parse_future(turn, start, "Başlangıç")
    if error:
        return fail(error)
    end_dt = parse_iso(end, turn.tz) if end else start_dt + timedelta(hours=1)  # type: ignore[operator]
    if end_dt is None or end_dt <= start_dt:  # type: ignore[operator]
        return fail("Bitiş, başlangıçtan sonra olmalı.")
    clash = overlapping(turn, start_dt, end_dt)  # type: ignore[arg-type]
    if clash:
        return fail("Bu saatte başka etkinlik var, eklenmedi. Kullanıcıya söyle ve başka saat öner.",
                    cakisan=[describe_event(turn, ev) for ev in clash[:3]])
    turn.actions.append({
        "type": "calendar_add",
        "title": title,
        "start_ms": to_millis(start_dt),  # type: ignore[arg-type]
        "end_ms": to_millis(end_dt),
        "location": clean_text(location, 120),
        "description": clean_text(notes, 500),
    })
    turn.notes.append(f"{title} takvime eklendi ({human_time(start_dt, turn.now)})")  # type: ignore[arg-type]
    return ok(eklendi=title, zaman=human_time(start_dt, turn.now))  # type: ignore[arg-type]


def t_calendar_update(turn: Turn, event_id: str, title: str | None, start: str | None, end: str | None, location: str | None) -> dict[str, Any]:
    ev = turn.event(event_id)
    if ev is None:
        return fail("Etkinlik bulunamadı; bağlamdaki [e...] kimliklerinden birini kullan.")
    if not ev.editable:
        return fail("Bu etkinlik telefonun takviminden geliyor, Zoro değiştiremez.")
    new_start, new_end = ev.start, ev.end
    if start:
        new_start, error = parse_future(turn, start, "Yeni başlangıç")
        if error:
            return fail(error)
        new_end = new_start + (ev.end - ev.start)  # type: ignore[operator]
    if end:
        parsed_end = parse_iso(end, turn.tz)
        if parsed_end is None:
            return fail("Bitiş geçersiz.")
        new_end = parsed_end
    if new_end <= new_start:  # type: ignore[operator]
        return fail("Bitiş, başlangıçtan sonra olmalı.")
    clash = overlapping(turn, new_start, new_end, exclude_id=ev.id)  # type: ignore[arg-type]
    if clash:
        return fail("Yeni saatte başka etkinlik var, değiştirilmedi.",
                    cakisan=[describe_event(turn, c) for c in clash[:3]])
    new_title = clean_text(title, 120) or ev.title
    turn.actions.append({
        "type": "calendar_update",
        "event_id": ev.id,
        "title": new_title,
        "start_ms": to_millis(new_start),  # type: ignore[arg-type]
        "end_ms": to_millis(new_end),  # type: ignore[arg-type]
        "location": clean_text(location, 120) if location is not None else ev.location,
    })
    turn.notes.append(f"{new_title} {human_time(new_start, turn.now)} olarak güncellendi")  # type: ignore[arg-type]
    return ok(guncellendi=new_title, zaman=human_time(new_start, turn.now))  # type: ignore[arg-type]


def t_calendar_delete(turn: Turn, event_id: str) -> dict[str, Any]:
    ev = turn.event(event_id)
    if ev is None:
        return fail("Etkinlik bulunamadı; bağlamdaki [e...] kimliklerinden birini kullan.")
    if not ev.editable:
        return fail("Bu etkinlik telefonun takviminden geliyor, Zoro silemez.")
    return request_confirmation(
        turn, "calendar_delete", {"event_id": ev.id, "title": ev.title},
        f"{describe_event(turn, ev)} etkinliğini takvimden sileyim mi?",
    )


def t_navigate(turn: Turn, destination: str, mode: str) -> dict[str, Any]:
    destination = clean_text(destination, 200)
    if not destination:
        return fail("Varış yeri boş.")
    turn.actions.append({"type": "navigate", "destination": destination, "mode": mode})
    turn.notes.append(f"{destination} için yol tarifi açılıyor")
    return ok(acilacak=destination)


def t_call_contact(turn: Turn, name: str, phone_number: str | None) -> dict[str, Any]:
    name = clean_text(name, 80)
    number = "".join(ch for ch in str(phone_number or "") if ch.isdigit() or ch == "+")
    if not name and not number:
        return fail("Kimin aranacağı belli değil.")
    turn.actions.append({"type": "call", "name": name, "number": number})
    turn.notes.append(f"{name or number} aranıyor")
    return ok(aranacak=name or number, not_="Telefon kişiyi rehberde bulur; bulamazsa kendisi söyler.")


def t_send_message(turn: Turn, app: str, recipient: str, text: str) -> dict[str, Any]:
    recipient, text = clean_text(recipient, 80), clean_text(text, 1000)
    if not recipient or not text:
        return fail("Alıcı ve mesaj metni gerekli.")
    turn.actions.append({"type": "message", "app": app, "recipient": recipient, "text": text})
    turn.notes.append(f"{recipient} için mesaj taslağı açılıyor")
    return ok(taslak_acilacak=True, not_="Mesaj otomatik gitmez; kullanıcı gönder tuşuna basar. Bunu söyle.")


def t_open_app(turn: Turn, app: str, query: str | None) -> dict[str, Any]:
    turn.actions.append({"type": "open_app", "app": app, "query": clean_text(query, 200)})
    turn.notes.append(f"{app} açılıyor")
    return ok(acilacak=app)


def t_set_alarm(turn: Turn, time: str, label: str | None) -> dict[str, Any]:
    try:
        hour, minute = (int(x) for x in str(time).strip().split(":")[:2])
        assert 0 <= hour <= 23 and 0 <= minute <= 59
    except (ValueError, AssertionError):
        return fail("Saat SS:DD biçiminde olmalı, örn. 07:30.")
    turn.actions.append({"type": "set_alarm", "hour": hour, "minute": minute, "label": clean_text(label, 80)})
    turn.notes.append(f"{hour:02d}:{minute:02d} alarmı kuruluyor")
    return ok(alarm=f"{hour:02d}:{minute:02d}")


def t_set_timer(turn: Turn, seconds: int, label: str | None) -> dict[str, Any]:
    seconds = int(seconds)
    if not 1 <= seconds <= 86400:
        return fail("Süre 1 saniye ile 24 saat arasında olmalı.")
    turn.actions.append({"type": "set_timer", "seconds": seconds, "label": clean_text(label, 80)})
    turn.notes.append("Zamanlayıcı kuruluyor")
    return ok(sure_saniye=seconds)


# ---------------------------------------------------------- confirmations

def request_confirmation(turn: Turn, kind: str, payload: dict[str, Any], question: str) -> dict[str, Any]:
    pending = turn.store.create_pending(kind, payload, question, turn.now, turn.cfg.confirm_ttl_s)
    turn.pending_created = pending
    return ok(durum="onay_bekleniyor", soru=question,
              not_="Henüz hiçbir şey silinmedi. Kullanıcı evet derse silinecek.")


def execute_pending(turn: Turn, pending: dict[str, Any]) -> str:
    """Run a confirmed destructive action. Returns the spoken result."""
    if not turn.store.close_pending(pending["id"], "done"):
        return "Bu işlem zaten sonuçlanmış."
    kind, payload = pending["kind"], pending["payload"]
    if kind == "delete_task":
        task = turn.store.delete_task(payload["task_id"])
        if task is None:
            return "Görev zaten silinmiş."
        turn.actions.append(cancel_action(task))
        turn.set_legacy(legacy_cancel("delete_task", task))
        return f"Sildim: {task['text']}."
    if kind == "clear_tasks":
        tasks = turn.store.clear_tasks()
        for task in tasks:
            turn.actions.append(cancel_action(task))
        turn.set_legacy({
            "action": "clear_tasks",
            "cancel_alarm_ids": [t["id"] for t in tasks],
            "cancel_due_ats": [t["due_at"] for t in tasks if t.get("due_at")],
        })
        return f"{len(tasks)} görevin hepsini sildim."
    if kind == "calendar_delete":
        turn.actions.append({"type": "calendar_delete", "event_id": payload["event_id"], "title": payload.get("title", "")})
        return f"Takvimden sildim: {payload.get('title', 'etkinlik')}."
    if kind == "forget_fact":
        fact = turn.store.delete_fact(payload["fact_id"])
        return "Bu bilgiyi hafızamdan sildim." if fact else "Bu bilgi zaten silinmiş."
    return "Bu işlemi tanımıyorum, hiçbir şey yapmadım."


# ------------------------------------------------------------- tool specs

def _fn(name: str, description: str, properties: dict[str, Any]) -> dict[str, Any]:
    return {
        "type": "function",
        "name": name,
        "description": description,
        "strict": True,
        "parameters": {
            "type": "object",
            "properties": properties,
            "required": list(properties),
            "additionalProperties": False,
        },
    }


S = {"type": "string"}
S_NULL = {"type": ["string", "null"]}

ToolFn = Callable[..., dict[str, Any]]

# name -> (capability or None, spec, implementation)
REGISTRY: dict[str, tuple[str | None, dict[str, Any], ToolFn]] = {}


def _register(cap: str | None, spec: dict[str, Any], fn: ToolFn) -> None:
    REGISTRY[spec["name"]] = (cap, spec, fn)


_register(None, _fn("add_task", "Zamanlı hatırlatma (alarm) ya da zamansız görev ekler. 'Bana hatırlat', 'unutmayayım', 'her gün 9'da' gibi istekler.", {
    "text": {**S, "description": "Hatırlatılacak şey, kısa (örn. 'İlacını iç')."},
    "due_at": {**S_NULL, "description": "Saat dilimli ISO-8601. Zamansız görevse null."},
    "repeat": {"type": "string", "enum": ["none", "daily"]},
}), t_add_task)
_register(None, _fn("complete_task", "Görevi/hatırlatmayı tamamlandı olarak işaretler.", {
    "task_id": {**S, "description": "Bağlamdaki [t:...] kimliği."},
}), t_complete_task)
_register(None, _fn("snooze_task", "Hatırlatmayı erteler. task_id null ise az önce çalan hatırlatma ertelenir.", {
    "task_id": S_NULL,
    "minutes": {"type": "integer", "description": "1-1440 dakika. Söylenmezse 10."},
}), t_snooze_task)
_register(None, _fn("edit_task", "Görevin metnini ya da zamanını değiştirir.", {
    "task_id": S,
    "text": S_NULL,
    "due_at": {**S_NULL, "description": "Yeni zaman, saat dilimli ISO-8601."},
}), t_edit_task)
_register(None, _fn("delete_task", "Bir görevi siler (kullanıcıdan onay istenir).", {
    "task_id": S,
}), t_delete_task)
_register(None, _fn("clear_tasks", "Tüm görevleri siler (kullanıcıdan onay istenir). Sadece açıkça 'hepsini sil' denirse.", {}), t_clear_tasks)
_register(None, _fn("remember", "Kişisel bir bilgiyi kalıcı hafızaya kaydeder ya da günceller.", {
    "text": {**S, "description": "Bilginin tam cümlesi, örn. 'Arabanın plakası 34 ABC 123'."},
    "key": {**S_NULL, "description": "Aynı konunun güncellenmesi için kısa anahtar, örn. 'araba plakası'."},
}), t_remember)
_register(None, _fn("forget", "Hafızadan bir bilgiyi siler (onay istenir).", {
    "fact_id": {**S, "description": "Bağlamdaki [f:...] kimliği."},
}), t_forget)
_register(None, _fn("get_weather", "Bir şehrin şu anki hava durumu ve 3 günlük tahmini.", {
    "city": {**S_NULL, "description": "Şehir/ilçe. null ise kullanıcının şehri."},
}), t_get_weather)
_register(None, _fn("get_exchange_rates", "TCMB günlük döviz kurları.", {
    "codes": {"type": "array", "items": S, "description": "Örn. ['USD','EUR']."},
}), t_get_exchange_rates)
_register(CAP_CALENDAR, _fn("calendar_add", "Takvime toplantı/randevu/etkinlik ekler.", {
    "title": S,
    "start": {**S, "description": "Saat dilimli ISO-8601."},
    "end": {**S_NULL, "description": "null ise 1 saat."},
    "location": S_NULL,
    "notes": S_NULL,
}), t_calendar_add)
_register(CAP_CALENDAR, _fn("calendar_update", "Zoro takvimindeki bir etkinliği taşır/değiştirir (erteleme dahil).", {
    "event_id": {**S, "description": "Bağlamdaki [e...] kimliği."},
    "title": S_NULL,
    "start": S_NULL,
    "end": S_NULL,
    "location": S_NULL,
}), t_calendar_update)
_register(CAP_CALENDAR, _fn("calendar_delete", "Zoro takvimindeki bir etkinliği siler (onay istenir).", {
    "event_id": S,
}), t_calendar_delete)
_register(CAP_NAVIGATE, _fn("navigate", "Google Haritalar'da yol tarifini başlatır.", {
    "destination": {**S, "description": "Adres ya da yer adı, örn. 'İstanbul Havalimanı'."},
    "mode": {"type": "string", "enum": ["driving", "walking", "transit"]},
}), t_navigate)
_register(CAP_CALL, _fn("call_contact", "Rehberdeki birini ya da bir numarayı arar.", {
    "name": {**S, "description": "Rehberdeki isim, örn. 'Okay'."},
    "phone_number": {**S_NULL, "description": "Kullanıcı numara söylediyse."},
}), t_call_contact)
_register(CAP_MESSAGE, _fn("send_message", "SMS ya da WhatsApp mesaj taslağı açar; kullanıcı kendisi gönderir.", {
    "app": {"type": "string", "enum": ["whatsapp", "sms"]},
    "recipient": {**S, "description": "Rehberdeki isim ya da numara."},
    "text": S,
}), t_send_message)
_register(CAP_OPEN_APP, _fn("open_app", "Telefonda bir uygulamayı açar; query varsa içinde arar (YouTube'da video, Spotify'da şarkı, tarayıcıda arama).", {
    "app": {"type": "string", "enum": OPEN_APPS},
    "query": S_NULL,
}), t_open_app)
_register(CAP_ALARM, _fn("set_alarm", "Telefonun saat uygulamasında alarm kurar (uyandırma).", {
    "time": {**S, "description": "SS:DD, 24 saat."},
    "label": S_NULL,
}), t_set_alarm)
_register(CAP_ALARM, _fn("set_timer", "Geri sayım zamanlayıcısı kurar.", {
    "seconds": {"type": "integer"},
    "label": S_NULL,
}), t_set_timer)

CONFIRM_SPEC = _fn("confirm_pending", "Onay bekleyen işleme kullanıcının cevabı.", {
    "answer": {"type": "string", "enum": ["yes", "no"]},
})


def tool_specs(turn: Turn) -> list[dict[str, Any]]:
    specs = []
    for cap, spec, _ in REGISTRY.values():
        if cap is None or turn.client.can(cap):
            specs.append(spec)
    if turn.pending:
        specs.append(CONFIRM_SPEC)
    if turn.cfg.web_search:
        specs.append({"type": "web_search"})
    return specs


def execute(turn: Turn, name: str, arguments: str) -> dict[str, Any]:
    turn.tools_called.append(name)
    if name == "confirm_pending":
        impl: ToolFn = t_confirm_pending
        allowed = bool(turn.pending)
    else:
        entry = REGISTRY.get(name)
        if entry is None:
            return fail(f"'{name}' diye bir araç yok.")
        cap, _, impl = entry
        allowed = cap is None or turn.client.can(cap)
    if not allowed:
        return fail("Bu telefon bu işlemi desteklemiyor.")
    try:
        args = json.loads(arguments or "{}")
        if not isinstance(args, dict):
            raise ValueError("arguments must be an object")
    except ValueError:
        return fail("Araç argümanları geçerli JSON değil.")
    try:
        return impl(turn, **args)
    except TypeError as error:
        return fail(f"Argümanlar hatalı: {error}")
    except Exception as error:  # never let one tool crash the turn
        return fail(f"Araç çalışırken hata oluştu ({type(error).__name__}).")
