"""Flask app: the v1 HTTP contract plus the v2 /ask fields."""

from __future__ import annotations

import hmac
import logging
import time
from datetime import timedelta
from typing import Any, Callable

from flask import Flask, Response, jsonify, request, stream_with_context
from werkzeug.exceptions import HTTPException

from . import costs, info, legacy_rules, settings as user_settings, voice
from .brain import AIUnavailable, Brain, briefing_message, build_response
from .config import Config, load_config
from .store import Store
from .timeutil import DAILY_MS, from_millis, iso, now_in, parse_iso, zone
from .tools import ALL_CAPS, CalendarEvent, CallEntry, ClientInfo, Turn, cancel_action

VERSION = "2.1.0"
SERVER_FEATURES = ["settings", "usage", "tts", "recent_calls", "calendar_write"]
MAX_TTS_CHARS = 1200
log = logging.getLogger("zoro.web")


def parse_client(data: dict[str, Any]) -> ClientInfo:
    raw = data.get("client")
    if not isinstance(raw, dict):
        return ClientInfo()
    try:
        version = int(raw.get("version") or 1)
    except (TypeError, ValueError):
        version = 1
    caps = raw.get("capabilities")
    caps = {c for c in caps if isinstance(c, str) and c in ALL_CAPS} if isinstance(caps, list) else set()
    return ClientInfo(version=version, capabilities=caps)


def parse_calendar(context: dict[str, Any], turn: Turn, limit: int = 80) -> list[CalendarEvent] | None:
    raw = context.get("calendar")
    if not isinstance(raw, list):
        return None
    window_start = turn.now - timedelta(days=1)
    window_end = turn.now + timedelta(days=31)
    events = []
    for item in raw[:300]:
        if not isinstance(item, dict):
            continue
        start = from_millis(item.get("start_ms"), turn.tz)
        end = from_millis(item.get("end_ms"), turn.tz)
        title = " ".join(str(item.get("title") or "").split())[:120]
        event_id = str(item.get("id") or "")[:200]
        if not start or not end or not title or not event_id or end < window_start or start > window_end:
            continue
        events.append((start, end, event_id, title, item))
    events.sort(key=lambda e: e[0])
    result = []
    for index, (start, end, event_id, title, item) in enumerate(events[:limit], 1):
        result.append(CalendarEvent(
            ref=f"e{index}", id=event_id, title=title, start=start, end=max(end, start),
            location=" ".join(str(item.get("location") or "").split())[:120],
            source=str(item.get("source") or "ZORO")[:60],
            all_day=bool(item.get("all_day")),
            writable=item.get("writable") is True,
        ))
    return result


def parse_calls(context: dict[str, Any], turn: Turn, limit: int = 10) -> list[CallEntry] | None:
    raw = context.get("recent_calls")
    if not isinstance(raw, list):
        return None
    oldest = turn.now - timedelta(hours=48)
    calls = []
    for item in raw[:50]:
        if not isinstance(item, dict):
            continue
        when = from_millis(item.get("time_ms"), turn.tz)
        number = "".join(ch for ch in str(item.get("number") or "") if ch.isdigit() or ch == "+")[:20]
        kind = str(item.get("type") or "")
        if when is None or when < oldest or when > turn.now + timedelta(minutes=5) or not number:
            continue
        if kind not in ("missed", "incoming", "outgoing", "rejected"):
            continue
        name = " ".join(str(item.get("name") or "").split())[:80]
        calls.append((when, name, number, kind))
    calls.sort(key=lambda c: c[0], reverse=True)
    return [CallEntry(ref=f"c{i}", name=name, number=number, kind=kind, when=when)
            for i, (when, name, number, kind) in enumerate(calls[:limit], 1)]


def create_app(cfg: Config | None = None, openai_factory: Callable[[], Any] | None = None) -> Flask:
    cfg = cfg or load_config()
    if not logging.getLogger().handlers:
        logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

    store = Store(cfg.db_path)
    imported = store.import_legacy(cfg.legacy_history_file, cfg.legacy_tasks_file, cfg.legacy_personal_file)
    if imported:
        log.info("legacy JSON imported: %s", imported)

    app = Flask(__name__)
    app.config["MAX_CONTENT_LENGTH"] = 512 * 1024
    app.config["JSON_AS_ASCII"] = False
    app.json.ensure_ascii = False  # type: ignore[attr-defined]
    app.extensions["zoro"] = {"cfg": cfg, "store": store}

    brain_holder: dict[str, Brain] = {}

    def brain() -> Brain:
        if "brain" not in brain_holder:
            if openai_factory is not None:
                client = openai_factory()
            else:
                from openai import OpenAI
                client = OpenAI(max_retries=1)
            brain_holder["brain"] = Brain(client)
        return brain_holder["brain"]

    def new_turn(data: dict[str, Any]) -> Turn:
        context = data.get("context") if isinstance(data.get("context"), dict) else {}
        tz = zone(context.get("tz") if isinstance(context.get("tz"), str) else None, cfg.timezone)
        turn = Turn(cfg=cfg, store=store, tz=tz, now=now_in(tz), client=parse_client(data))
        turn.calendar = parse_calendar(context, turn)
        turn.recent_calls = parse_calls(context, turn)
        current = user_settings.load(store, cfg)
        turn.web_search = bool(current["web_search"]) and user_settings.paid_allowed(
            store, current, turn.now.strftime("%Y-%m"))
        note = context.get("client_note")
        turn.client_note = " ".join(str(note).split())[:500] if isinstance(note, str) else ""
        turn.pending = store.active_pending(turn.now)
        return turn

    @app.before_request
    def check_key():
        if request.path == "/health" or not cfg.api_key:
            return None
        given = request.headers.get("X-Zoro-Key", "")
        if hmac.compare_digest(given.encode(), cfg.api_key.encode()):
            return None
        return jsonify({"success": False, "reply": "Yetkisiz istek.", "error": "unauthorized"}), 401

    @app.errorhandler(HTTPException)
    def http_error(error: HTTPException):
        return jsonify({"success": False, "reply": "İstek işlenemedi.", "error": error.name}), error.code or 500

    @app.errorhandler(Exception)
    def unexpected(error: Exception):
        log.exception("unhandled error")
        return jsonify({"success": False, "reply": "Sunucuda beklenmeyen bir hata oluştu.", "error": "internal"}), 500

    @app.get("/health")
    def health():
        return jsonify({"ok": True, "service": "zoro", "version": VERSION})

    def run_brain(turn: Turn, message: str, rules_fallback: bool = True) -> dict[str, Any] | None:
        # The v1 app gives up after 10 s (OkHttp default read timeout).
        budget = cfg.turn_budget_v2_s if turn.client.capabilities else cfg.turn_budget_v1_s
        turn.deadline = time.monotonic() + budget
        try:
            return brain().answer(turn, message)
        except AIUnavailable as error:
            log.error("AI unavailable: %s", error)
            if not rules_fallback:
                return None
            if turn.tools_called:
                # Some tools already ran: never repeat them through the rules.
                done = ". ".join(turn.notes)
                reply = (done + ". " if done else "") + "Gerisini tamamlayamadım, tekrar dener misiniz?"
            else:
                try:
                    reply = legacy_rules.fallback(turn, message)
                except Exception:
                    log.exception("offline fallback failed")
                    reply = legacy_rules.OFFLINE_NOTE + " Biraz sonra tekrar deneyin."
            store.add_message("user", message)
            store.add_message("assistant", reply, turn.actions or None)
            return build_response(turn, reply, error="ai_unavailable")

    @app.post("/ask")
    def ask():
        data = request.get_json(silent=True) or {}
        if not isinstance(data, dict):
            data = {}
        message = " ".join(str(data.get("message", "")).split())[: cfg.max_message_chars]
        if not message:
            return jsonify({"reply": "Mesaj boş olamaz.", "action": "", "actions": []}), 400
        return jsonify(run_brain(new_turn(data), message))

    @app.post("/briefing")
    def briefing():
        data = request.get_json(silent=True) or {}
        turn = new_turn(data if isinstance(data, dict) else {})
        turn.feature = "sabah_ozeti"
        # The briefing must not answer an open "silinsin mi?" or use up the phone's note.
        turn.pending = None
        turn.client_note = ""
        result = run_brain(turn, briefing_message(turn), rules_fallback=False)
        if result is None:
            # The phone reads its own local briefing instead of a keyword-rule sentence.
            return jsonify({"success": False, "reply": "", "error": "ai_unavailable", "actions": []}), 503
        return jsonify(result)

    # ------------------------------------- settings, costs, natural voice

    def openai_client() -> Any:
        return brain().client

    def usd_try() -> float | None:
        rate = info.exchange_rates(["USD"]).get("kurlar", {}).get("USD", {})
        return rate.get("doviz_satis")

    @app.get("/settings")
    def get_settings():
        return jsonify({"success": True, "settings": user_settings.load(store, cfg),
                        "voices": user_settings.VOICES})

    @app.put("/settings")
    def put_settings():
        accepted, errors = user_settings.validate(request.get_json(silent=True))
        if errors:
            return jsonify({"success": False, "reply": " ".join(errors), "errors": errors}), 400
        saved = user_settings.save(store, cfg, accepted)
        return jsonify({"success": True, "settings": saved, "voices": user_settings.VOICES})

    @app.get("/usage")
    def usage():
        now = now_in(zone(cfg.timezone))
        current = user_settings.load(store, cfg)
        return jsonify({"success": True, **costs.summary(store, current, cfg.model, cfg.tts_model, now, usd_try)})

    @app.post("/tts")
    def tts():
        data = request.get_json(silent=True) or {}
        text = " ".join(str(data.get("text") or "").split()) if isinstance(data, dict) else ""
        if not text or len(text) > MAX_TTS_CHARS:
            return jsonify({"success": False, "error": "bad_text"}), 400
        now = now_in(zone(cfg.timezone))
        current = user_settings.load(store, cfg)
        preview = isinstance(data, dict) and data.get("preview") is True
        if not current["natural_voice"] and not preview:
            return jsonify({"success": False, "error": "disabled"}), 409
        if not user_settings.paid_allowed(store, current, now.strftime("%Y-%m")):
            return jsonify({"success": False, "error": "budget"}), 402
        chosen = data.get("voice") if preview and data.get("voice") in user_settings.VOICES else current["voice"]
        client = openai_client()
        if hasattr(client, "with_options"):
            client = client.with_options(max_retries=0)  # never outwait the phone (8 s read timeout)
        events = voice.speech_events(client, cfg.tts_model, chosen, text, timeout=6.0)
        try:
            first = next(events)  # surface upstream errors as a status code, not a broken stream
        except (voice.VoiceUnavailable, StopIteration) as error:
            log.warning("tts unavailable: %s", error)
            return jsonify({"success": False, "error": "voice_unavailable"}), 502

        def stream():
            pending = [first]
            sent = 0
            recorded = False
            try:
                while True:
                    kind, payload = pending.pop() if pending else next(events)
                    if kind == "audio":
                        sent += len(payload)
                        yield payload
                    elif kind == "usage":
                        costs.record_speech(store, now, cfg.tts_model, payload)
                        recorded = True
            except StopIteration:
                return
            except voice.VoiceUnavailable as error:
                log.warning("tts stream cut: %s", error)
            finally:
                events.close()
                if not recorded and sent:
                    # Cut short (phone gone, network): OpenAI still bills it, so estimate.
                    costs.record_speech(store, now, cfg.tts_model, voice.estimated_usage(text, sent))

        return Response(stream_with_context(stream()), mimetype="application/octet-stream",
                        headers={"X-Zoro-Audio": f"pcm_s16le;rate={voice.SAMPLE_RATE};channels=1",
                                 "Cache-Control": "no-store"})

    # ------------------------------------------------ v1 task endpoints

    @app.get("/tasks")
    def list_tasks():
        tasks = store.list_tasks()
        return jsonify({
            "success": True,
            "tasks": [{
                "number": i,
                "id": t["id"],
                "text": t["text"],
                "due_at": t["due_at"],
                "done": t["done"],
                "repeat_interval": t["repeat_interval"],
            } for i, t in enumerate(tasks, 1)],
        })

    @app.post("/tasks/<task_id>/complete")
    def complete_task(task_id: str):
        task = store.get_task(task_id)
        if task is None:
            return jsonify({"success": False, "reply": "Görev bulunamadı."}), 404
        if task["done"]:
            return jsonify({"success": True, "reply": "Görev zaten tamamlanmış.",
                            "alarm_id": task["id"], "due_at": task["due_at"]})
        task = store.update_task(task_id, done=True, notified=True)
        return jsonify({"success": True, "reply": "Görev tamamlandı.", "alarm_id": task["id"],  # type: ignore[index]
                        "due_at": task["due_at"], "task_text": task["text"]})  # type: ignore[index]

    @app.post("/tasks/<task_id>/delete")
    def delete_task(task_id: str):
        # Deleted from the task screen, where the app already shows a confirm dialog.
        task = store.delete_task(task_id)
        if task is None:
            return jsonify({"success": False, "reply": "Görev bulunamadı."}), 404
        return jsonify({"success": True, "reply": "Görev silindi.", "alarm_id": task["id"],
                        "due_at": task["due_at"], "task_text": task["text"],
                        "actions": [cancel_action(task)]})

    @app.post("/tasks/<task_id>/edit")
    def edit_task(task_id: str):
        data = request.get_json(silent=True)
        if not isinstance(data, dict):
            return jsonify({"success": False, "reply": "Gecersiz istek."}), 400
        new_text, new_due = data.get("text"), data.get("due_at")
        if not isinstance(new_text, str) or not new_text.strip() or len(new_text.strip()) > 500:
            return jsonify({"success": False, "reply": "Gorev metni 1-500 karakter olmali."}), 400
        fields: dict[str, Any] = {"text": " ".join(new_text.split())}
        if new_due is not None:
            tz = zone(cfg.timezone)
            parsed = parse_iso(new_due, tz)
            if parsed is None:
                return jsonify({"success": False, "reply": "Tarih veya saat gecersiz."}), 400
            if parsed <= now_in(tz):
                return jsonify({"success": False, "reply": "Gecmis bir saate gorev konulamaz."}), 400
            fields.update(due_at=iso(parsed), notified=False)
        if store.get_task(task_id) is None:
            return jsonify({"success": False, "reply": "Gorev bulunamadi."}), 404
        task = store.update_task(task_id, **fields)
        return jsonify({"success": True, "reply": "Gorev guncellendi.", "task_id": task["id"],  # type: ignore[index]
                        "text": task["text"], "due_at": task["due_at"],  # type: ignore[index]
                        "repeat_interval": task["repeat_interval"]})  # type: ignore[index]

    # ------------------------------------------------------- memory

    @app.get("/memory")
    def list_memory():
        return jsonify({"success": True, "facts": store.list_facts()})

    @app.post("/memory/import")
    def import_memory():
        """One-off upload of the phone's local memories (ZoroMemoryManager)."""
        data = request.get_json(silent=True) or {}
        items = data.get("items") if isinstance(data, dict) else None
        if not isinstance(items, list):
            return jsonify({"success": False, "reply": "items listesi gerekli."}), 400
        created = 0
        for item in items[:1000]:
            text = item.get("text") if isinstance(item, dict) else item
            if isinstance(text, str) and text.strip():
                _, is_new = store.upsert_fact(text.strip()[:1000], source="phone")
                created += int(is_new)
        return jsonify({"success": True, "imported": created, "total": len(store.list_facts())})

    @app.get("/version")
    def version():
        return jsonify({"version": VERSION, "model": cfg.model, "daily_ms": DAILY_MS,
                        "features": SERVER_FEATURES})

    return app
