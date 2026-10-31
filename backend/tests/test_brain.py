import json

import pytest

from conftest import Harness, at, call, ms, resp
from zoro.brain import yes_no
from zoro.timeutil import DAILY_MS


# ------------------------------------------------------------- basics

def test_health_is_open_and_api_key_guards_everything_else(tmp_path):
    h = Harness(tmp_path, api_key="s3cret")
    assert h.client.get("/health").status_code == 200
    assert h.client.get("/tasks").status_code == 401
    assert h.client.post("/ask", json={"message": "merhaba"}).status_code == 401
    assert h.client.get("/tasks", headers={"X-Zoro-Key": "wrong"}).status_code == 401
    assert h.client.get("/tasks", headers={"X-Zoro-Key": "s3cret"}).status_code == 200


def test_empty_message_is_400_with_reply(h):
    r = h.client.post("/ask", json={"message": "   "})
    assert r.status_code == 400
    assert r.get_json()["reply"]


def test_plain_chat_reply_and_markdown_is_stripped(h):
    h.script(resp("**Merhaba** Zafer Bey!\n- nasılsınız?"))
    out = h.ask("selam")
    assert out["reply"] == "Merhaba Zafer Bey! nasılsınız?"
    assert out["expect_reply"] is True  # ends with a question
    assert out["actions"] == [] and out["action"] == ""


def test_every_message_goes_to_the_model_no_keyword_shortcuts(h):
    # v1: "sil" + "görev" in any order deleted a task before the AI saw it.
    task = h.store.add_task("Silivri ziyareti", at(1, 10), 0)
    h.script(resp("Silivri ziyaretiniz yarın 10:00'da."))
    out = h.ask("Silivri görevim ne zaman sil bunu bilmiyorum")
    assert len(h.calls) == 1
    assert h.store.get_task(task["id"]) is not None
    assert "Silivri" in out["reply"]


def test_context_has_time_tasks_calendar_and_facts(h):
    h.store.add_task("İlaç iç", at(1, 9), DAILY_MS)
    h.store.upsert_fact("En sevdiği takım Galatasaray", key="takım")
    start = at(2, 14)
    h.script(resp("Tamam."))
    h.ask("toplantım ne zaman", v2=True, calendar=[{
        "id": "abc", "title": "Silivri toplantısı", "start_ms": ms(start), "end_ms": ms(start) + 3600_000,
        "location": "Silivri", "source": "ZORO", "all_day": False,
    }])
    items = h.calls[0]["input"]
    developer = next(i["content"] for i in items if i.get("role") == "developer")
    assert "Şu an:" in developer and "ISO:" in developer
    assert "[e1]" in developer and "Silivri toplantısı" in developer and "(Zoro)" in developer
    assert "[t:" in developer and "İlaç iç" in developer and "her gün 09:00" in developer
    assert "Galatasaray" in developer
    assert items[-1] == {"role": "user", "content": "toplantım ne zaman"}


def test_history_is_saved_and_sent_back(h):
    h.script(resp("Merhaba."), resp("Tabii."))
    h.ask("merhaba")
    h.ask("bir şey soracağım")
    roles = [i.get("role") for i in h.calls[1]["input"]]
    assert roles[:2] == ["user", "assistant"]
    assert h.calls[1]["input"][0]["content"] == "merhaba"


# ------------------------------------------------------- tasks / reminders

def test_add_task_returns_v1_fields_and_v2_action(h):
    due = at(1, 9)
    h.script(resp(calls=[call("add_task", text="Su iç", due_at=due, repeat="none")]),
             resp("Yarın 09:00'da su içmenizi hatırlatacağım."))
    out = h.ask("yarın sabah 9'da su içmemi hatırlat")
    assert out["action"] == "add_task"
    assert out["task_text"] == "Su iç"
    assert out["due_at"].startswith(due[:16])
    assert out["alarm_id"] and out["repeat_interval"] == 0
    assert out["actions"][0]["type"] == "schedule_reminder"
    assert out["actions"][0]["alarm_id"] == out["alarm_id"]
    # second model call continues the first one with the tool output
    assert h.calls[1]["previous_response_id"]
    assert h.tool_output(1)["ok"] is True
    tasks = h.client.get("/tasks").get_json()["tasks"]
    assert tasks[0]["text"] == "Su iç" and tasks[0]["number"] == 1


def test_past_time_is_rejected_with_reason(h):
    h.script(resp(calls=[call("add_task", text="x", due_at=at(-1, 9), repeat="none")]), resp("O saat geçti."))
    out = h.ask("dün 9'da hatırlat")
    result = h.tool_output(1)
    assert result["ok"] is False and "geçmişte" in result["error"]
    assert out["action"] == "" and h.store.list_tasks() == []


def test_daily_reminder_with_passed_time_starts_tomorrow(h):
    h.script(resp(calls=[call("add_task", text="İlaç", due_at=at(0, 0, 1), repeat="daily")]), resp("Tamam."))
    out = h.ask("her gün gece 00:01'de ilacımı hatırlat")
    assert out["repeat_interval"] == DAILY_MS
    assert h.tool_output(1)["ok"] is True


def test_complete_and_edit_and_snooze(h):
    task = h.store.add_task("Rapor", at(1, 11), 0)
    ref = "t:" + task["id"][:6]
    h.script(resp(calls=[call("edit_task", task_id=ref, text=None, due_at=at(1, 15))]), resp("Saat 15:00'e aldım."))
    out = h.ask("raporu yarın 3'e al")
    assert out["action"] == "add_task" and out["alarm_id"] == task["id"]  # same alarm id = reschedule
    assert [a["type"] for a in out["actions"]] == ["cancel_reminder", "schedule_reminder"]

    h.script(resp(calls=[call("snooze_task", task_id=ref, minutes=30)]), resp("30 dakika erteledim."))
    out = h.ask("raporu yarım saat ertele")
    assert out["action"] == "add_task" and out["alarm_id"] != task["id"]

    h.script(resp(calls=[call("complete_task", task_id=ref)]), resp("Tamamlandı."))
    out = h.ask("raporu bitirdim")
    assert out["action"] == "complete_task"
    assert h.store.get_task(task["id"])["done"] is True


def test_unknown_task_ref_returns_error_to_model(h):
    h.script(resp(calls=[call("complete_task", task_id="t:ffffff")]), resp("Bulamadım."))
    h.ask("şunu bitirdim")
    assert h.tool_output(1)["ok"] is False


# --------------------------------------------------------- confirmation

def _delete_question(h):
    task = h.store.add_task("Silivri toplantı hatırlatması", at(1, 14), 0)
    h.script(resp(calls=[call("delete_task", task_id="t:" + task["id"][:6])]))
    out = h.ask("silivri hatırlatmasını sil")
    return task, out


def test_delete_asks_first_and_deletes_on_yes(h):
    task, out = _delete_question(h)
    assert out["expect_reply"] is True and out["pending"]
    assert "sileyim mi" in out["reply"]
    assert out["action"] == ""  # nothing cancelled on the phone yet
    assert h.store.get_task(task["id"]) is not None

    out = h.ask("evet")  # no model call scripted: must be handled locally
    assert len(h.calls) == 1
    assert out["reply"].startswith("Sildim")
    assert out["action"] == "delete_task" and out["alarm_id"] == task["id"]
    assert out["cancel_alarm_ids"] == [task["id"]]
    assert h.store.get_task(task["id"]) is None


def test_delete_cancelled_on_no(h):
    task, _ = _delete_question(h)
    out = h.ask("hayır silme")
    assert "vazgeçtim" in out["reply"]
    assert h.store.get_task(task["id"]) is not None


def test_unanswered_question_expires_so_a_later_yes_cannot_delete(h):
    task, _ = _delete_question(h)
    h.script(resp("Hava güneşli."))
    h.ask("bugün hava nasıl olacak acaba söyler misin")  # ignores the question
    h.script(resp("Neye evet?"))
    out = h.ask("evet")
    assert out["reply"] == "Neye evet?"
    assert h.store.get_task(task["id"]) is not None


def test_model_can_confirm_with_tool_for_long_answers(h):
    task, _ = _delete_question(h)
    h.script(resp(calls=[call("confirm_pending", answer="yes")]), resp("Tamam, sildim."))
    out = h.ask("evet evet onu silebilirsin artık hiç gerek kalmadı")
    assert "confirm_pending" in h.tool_names(1)
    assert h.store.get_task(task["id"]) is None
    assert out["action"] == "delete_task"


def test_clear_all_needs_confirmation(h):
    for i in range(3):
        h.store.add_task(f"g{i}", at(1, 10 + i), 0)
    h.script(resp(calls=[call("clear_tasks")]))
    out = h.ask("tüm görevlerimi sil")
    assert "3 görevin hepsini" in out["reply"]
    out = h.ask("evet")
    assert out["action"] == "clear_tasks" and len(out["cancel_alarm_ids"]) == 3
    assert h.store.list_tasks() == []


@pytest.mark.parametrize("text,expected", [
    ("evet", "yes"), ("Evet, sil.", "yes"), ("tamam", "yes"), ("Zoro evet", "yes"), ("iptal et", "yes"),
    ("hayır", "no"), ("vazgeç", "no"), ("silme", "no"), ("iptal etme", "no"), ("tamam ama silme", "no"),
    ("Silivri", None), ("yarın ne var", None), ("bugün hava nasıl olacak acaba söyler misin", None),
    ("çok güzel", None), ("durum ne", None),
])
def test_yes_no(text, expected):
    assert yes_no(text) == expected


# ------------------------------------------------------------- calendar

def _event(eid, title, start_iso, minutes=60, source="ZORO"):
    return {"id": eid, "title": title, "start_ms": ms(start_iso), "end_ms": ms(start_iso) + minutes * 60_000,
            "location": "", "source": source, "all_day": False}


def test_calendar_tools_only_for_v2_clients(h):
    h.script(resp("Merhaba."), resp("Merhaba."))
    h.ask("merhaba")
    assert "calendar_add" not in h.tool_names(0) and "navigate" not in h.tool_names(0)
    h.ask("merhaba", v2=True)
    assert {"calendar_add", "navigate", "call_contact", "send_message", "set_alarm"} <= h.tool_names(1)


def test_hallucinated_phone_tool_is_refused_for_v1(h):
    h.script(resp(calls=[call("navigate", destination="İstanbul", mode="driving")]), resp("Yapamıyorum."))
    out = h.ask("beni istanbula götür")
    assert h.tool_output(1)["ok"] is False
    assert out["actions"] == []


def test_calendar_add_conflict_is_reported(h):
    start = at(1, 14)
    h.script(resp(calls=[call("calendar_add", title="Bayram ziyareti", start=at(1, 14, 30), end=None, location=None, notes=None)]),
             resp("O saatte Silivri toplantınız var."))
    out = h.ask("yarın 14:30 bayram ziyareti ekle", v2=True, calendar=[_event("z1", "Silivri toplantısı", start)])
    result = h.tool_output(1)
    assert result["ok"] is False and result["cakisan"]
    assert out["actions"] == []


def test_calendar_add_builds_phone_action(h):
    start = at(2, 10)
    h.script(resp(calls=[call("calendar_add", title="Dişçi", start=start, end=None, location="Kadıköy", notes=None)]),
             resp("Ekledim."))
    out = h.ask("öbür gün 10'da dişçi randevusu ekle", v2=True, calendar=[])
    action = out["actions"][0]
    assert action["type"] == "calendar_add" and action["title"] == "Dişçi"
    assert action["end_ms"] - action["start_ms"] == 3600_000


def test_calendar_update_keeps_duration(h):
    start = at(1, 14)
    h.script(resp(calls=[call("calendar_update", event_id="e1", title=None, start=at(1, 16), end=None, location=None)]),
             resp("16:00'ya aldım."))
    out = h.ask("silivri toplantısını 4'e ertele", v2=True, calendar=[_event("z1", "Silivri", start, 90)])
    action = out["actions"][0]
    assert action["type"] == "calendar_update" and action["event_id"] == "z1"
    assert action["end_ms"] - action["start_ms"] == 90 * 60_000


def test_phone_calendar_events_are_read_only(h):
    start = at(1, 14)
    h.script(resp(calls=[call("calendar_delete", event_id="e1")]), resp("O etkinliği silemem."))
    out = h.ask("toplantıyı sil", v2=True, calendar=[_event("android_9_1", "Aile", start, source="ANDROID:Google")])
    assert h.tool_output(1)["ok"] is False and out["pending"] is None


def test_calendar_delete_confirmation_flow(h):
    start = at(1, 14)
    cal = [_event("z1", "Silivri toplantısı", start)]
    h.script(resp(calls=[call("calendar_delete", event_id="e1")]))
    out = h.ask("silivri toplantısını iptal et", v2=True, calendar=cal)
    assert "sileyim mi" in out["reply"] and out["actions"] == []
    out = h.ask("evet", v2=True, calendar=cal)
    assert out["actions"] == [{"type": "calendar_delete", "event_id": "z1", "title": "Silivri toplantısı"}]


# -------------------------------------------------------- phone actions

def test_navigate_call_message_for_v2(h):
    h.script(resp(calls=[call("navigate", destination="İstanbul", mode="driving"),
                         call("send_message", app="whatsapp", recipient="Okay", text="Yola çıktım")]),
             resp("Yol tarifini açıyorum, Okay için mesaj taslağı hazır, göndermek size kalmış."))
    out = h.ask("beni istanbula götür ve okaya yola çıktım yaz", v2=True)
    types = [a["type"] for a in out["actions"]]
    assert types == ["navigate", "message"]


def test_bad_tool_arguments_do_not_crash(h):
    h.script(resp(calls=[("add_task", "{bozuk json")]), resp("Bir sorun oldu."))
    out = h.ask("hatırlat")
    assert h.tool_output(1)["ok"] is False
    assert out["reply"] == "Bir sorun oldu."


def test_tool_round_limit_stops_the_loop(tmp_path):
    h = Harness(tmp_path, max_tool_rounds=2)
    weather = call("get_weather", city="Bursa")
    h.script(resp(calls=[call("remember", text="a", key=None)]), resp(calls=[weather]), resp(calls=[weather]))
    out = h.ask("döngü")
    assert len(h.calls) == 3
    assert out["reply"]  # falls back to the notes


# ----------------------------------------------------------- AI failure

def test_ai_failure_returns_200_and_offline_reminder_still_works(h):
    h.script(RuntimeError("quota"))
    out = h.ask("10 dakika sonra su içmemi hatırlat")
    assert out["error"] == "ai_unavailable"
    assert out["action"] == "add_task" and out["task_text"] == "su içmemi"
    assert len(h.store.list_tasks()) == 1


def test_ai_failure_never_deletes(h):
    task = h.store.add_task("Rapor", at(1, 9), 0)
    h.script(RuntimeError("down"))
    out = h.ask("1 numaralı görevi sil")
    assert "Silme" in out["reply"]
    assert h.store.get_task(task["id"]) is not None


def test_ai_failure_generic_message(h):
    h.script(RuntimeError("down"))
    out = h.ask("bugün haberlerde ne var")
    assert "ulaşamıyorum" in out["reply"]


# ---------------------------------------------------------------- misc

def test_briefing_uses_the_model(h):
    h.script(resp("Günaydın."))
    r = h.client.post("/briefing", json={"client": {"version": 2, "capabilities": ["calendar"]}, "context": {"calendar": []}})
    assert r.get_json()["reply"] == "Günaydın."
    assert "brifing" in h.calls[0]["input"][-1]["content"]


def test_memory_import_dedupes(h):
    r = h.client.post("/memory/import", json={"items": [{"text": "Plaka 34 ABC 12"}, {"text": "plaka 34 abc 12"}, "Kan grubu A+"]})
    assert r.get_json()["imported"] == 2
    r = h.client.post("/memory/import", json={"items": ["Kan grubu A+"]})
    assert r.get_json()["imported"] == 0


def test_remember_tool_upserts_by_key(h):
    h.script(resp(calls=[call("remember", text="Arabanın rengi beyaz", key="araba rengi")]), resp("Kaydettim."),
             resp(calls=[call("remember", text="Arabanın rengi siyah", key="araba rengi")]), resp("Güncelledim."))
    h.ask("arabamın rengi beyaz bunu unutma")
    h.ask("arabamı siyaha boyattım")
    facts = h.store.list_facts()
    assert len(facts) == 1 and facts[0]["text"] == "Arabanın rengi siyah"


def test_reasoning_effort_is_sent_only_when_configured(tmp_path):
    h = Harness(tmp_path, reasoning_effort="low")
    h.script(resp("ok"))
    h.ask("x")
    assert h.calls[0]["reasoning"] == {"effort": "low"}
    h2 = Harness(tmp_path / "b")
    h2.script(resp("ok"))
    h2.ask("x")
    assert "reasoning" not in h2.calls[0]


def test_say_skips_the_second_model_round(h):
    h.script(resp(calls=[call("add_task", text="Su iç", due_at=at(1, 9), repeat="none",
                              say="Yarın 09:00'da su içmenizi hatırlatacağım.")]))
    out = h.ask("yarın 9'da su içmemi hatırlat")
    assert len(h.calls) == 1
    assert out["reply"] == "Yarın 09:00'da su içmenizi hatırlatacağım."
    assert out["action"] == "add_task"
    assert "say" in h.calls[0]["tools"][0]["parameters"]["required"]


def test_say_is_ignored_when_the_tool_fails(h):
    h.script(resp(calls=[call("add_task", text="x", due_at=at(-1, 9), repeat="none", say="Kurdum.")]),
             resp("O saat geçmiş, yeni bir saat söyler misiniz?"))
    out = h.ask("dün 9'da hatırlat")
    assert len(h.calls) == 2
    assert out["reply"].startswith("O saat geçmiş")


def test_info_tools_always_get_a_second_round(h, monkeypatch):
    from zoro import info
    monkeypatch.setattr(info, "weather", lambda city, tz: {"ok": True, "simdi": {"sicaklik": 18}})
    h.script(resp(calls=[call("get_weather", city="İstanbul")]), resp("İstanbul'da hava 18 derece."))
    out = h.ask("hava nasıl")
    assert len(h.calls) == 2 and "18" in out["reply"]
    assert "say" not in h.calls[0]["tools"][[t["name"] for t in h.calls[0]["tools"]].index("get_weather")]["parameters"]["properties"]


def test_reply_is_tts_friendly(h):
    h.script(resp("Yarın 14:00–15:00 arası toplantınız var — yağış ihtimali %20."))
    out = h.ask("yarın ne var")
    assert out["reply"] == "Yarın 14:00 ile 15:00 arası toplantınız var, yağış ihtimali yüzde 20."
