import json
from datetime import datetime

import pytest

from conftest import TZ, Harness, at, call, ms, resp
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
    assert [a["type"] for a in out["actions"]] == ["schedule_reminder"]

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
    assert "silinsin mi" in out["reply"]
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
    assert "3 görevin hepsi silinsin mi" in out["reply"]
    out = h.ask("evet")
    assert out["action"] == "clear_tasks" and len(out["cancel_alarm_ids"]) == 3
    assert h.store.list_tasks() == []


@pytest.mark.parametrize("text,expected", [
    ("evet", "yes"), ("Evet, sil.", "yes"), ("tamam", "yes"), ("Zoro evet", "yes"), ("iptal et", None), ("tamam iptal et", None),
    ("hayır", "no"), ("vazgeç", "no"), ("silme", "no"), ("iptal etme", "no"), ("tamam ama silme", "no"),
    ("Silivri", None), ("yarın ne var", None), ("bugün hava nasıl olacak acaba söyler misin", None),
    ("çok güzel", None), ("durum ne", None),
    # review r1: these used to delete
    ("Peki saat kaç", None), ("olur mu acaba", None), ("tamam annemi ara", None), ("sil diğerini", None),
    ("tamam sileme", None), ("Zoro devam et müziği", None), ("evet mi?", None), ("evet lütfen", "yes"),
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
    assert "silinsin mi" in out["reply"] and out["actions"] == []
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
    assert "sabah özeti" in h.calls[0]["input"][-1]["content"]


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


# ------------------------------------------------- review r1 regressions

def test_say_path_also_closes_an_unanswered_question(h):
    task, _ = _delete_question(h)
    h.script(resp(calls=[call("add_task", text="Okay'ı ara", due_at=at(1, 9), repeat="none", say="Kurdum.")]))
    h.ask("yarın sabah dokuzda okayı aramamı hatırlat lütfen")
    h.script(resp("Neye tamam?"))
    h.ask("tamam")
    assert h.store.get_task(task["id"]) is not None


def test_ai_down_closes_the_question_too(h):
    task, _ = _delete_question(h)
    h.script(RuntimeError("down"))
    h.ask("bugün hava nasıl olacak acaba")
    h.script(resp("?"))
    h.ask("tamam")
    assert h.store.get_task(task["id"]) is not None


def test_offline_fallback_handles_9da_without_500(h):
    h.script(RuntimeError("down"))
    out = h.ask("yarın 9da ilaç içmeyi hatırlat")
    assert out["error"] == "ai_unavailable" and out["action"] == "add_task"
    assert "T09:00" in out["due_at"]


def test_round_two_failure_does_not_duplicate_the_reminder(h):
    h.script(resp(calls=[call("add_task", text="İlaç iç", due_at=at(1, 9), repeat="none")]), RuntimeError("timeout"))
    out = h.ask("yarın saat 9 ilaç içmeyi hatırlat")
    assert len(h.store.list_tasks()) == 1
    assert out["action"] == "add_task" and "İlaç iç" in out["reply"]


def test_v1_app_gets_one_alarm_per_turn(h):
    h.script(resp(calls=[call("add_task", text="İlaç", due_at=at(1, 9), repeat="none"),
                         call("add_task", text="Toplantı", due_at=at(1, 10), repeat="none")]),
             resp("İlacı kurdum, toplantıyı ayrıca söyleyin."))
    out = h.ask("yarın 9'da ilaç 10'da toplantı hatırlat")
    assert [t["text"] for t in h.store.list_tasks()] == ["İlaç"]
    assert h.tool_output(1, 1)["ok"] is False
    assert out["task_text"] == "İlaç"


def test_v2_app_gets_every_alarm(h):
    h.script(resp(calls=[call("add_task", text="İlaç", due_at=at(1, 9), repeat="none", say="Tamam."),
                         call("add_task", text="Toplantı", due_at=at(1, 10), repeat="none", say="Tamam.")]))
    out = h.ask("yarın 9'da ilaç 10'da toplantı hatırlat", v2=True)
    assert [a["type"] for a in out["actions"]] == ["schedule_reminder", "schedule_reminder"]


def test_completing_a_daily_reminder_keeps_the_series(h):
    task = h.store.add_task("Tansiyon ilacı", at(1, 9), DAILY_MS)
    h.script(resp(calls=[call("complete_task", task_id="t:" + task["id"][:6])]), resp("Tamam, yarın yine hatırlatırım."))
    out = h.ask("ilacımı içtim")
    assert h.store.get_task(task["id"])["done"] is False
    assert out["actions"] == [] and out["action"] == ""
    assert h.tool_output(1)["tekrar_devam"] is True


def test_editing_an_old_daily_task_rearms_the_next_occurrence(h):
    task = h.store.add_task("İlaç", at(-3, 8), DAILY_MS)
    h.script(resp(calls=[call("edit_task", task_id="t:" + task["id"][:6], text="Tansiyon ilacı", due_at=None)]),
             resp("Adını değiştirdim."))
    out = h.ask("ilacın adını tansiyon ilacı yap")
    action = out["actions"][0]
    assert action["type"] == "schedule_reminder" and action["text"] == "Tansiyon ilacı"
    assert datetime.fromisoformat(action["due_at"]) > datetime.now(TZ)


def test_editing_an_overdue_one_shot_text_sends_no_alarm(h):
    task = h.store.add_task("Eski iş", at(-2, 8), 0)
    h.script(resp(calls=[call("edit_task", task_id="t:" + task["id"][:6], text="Eski iş (bitti)", due_at=None)]), resp("Tamam."))
    out = h.ask("eski işin adını değiştir")
    assert out["actions"] == [] and out["action"] == ""


def test_two_deletes_become_one_question(h):
    a = h.store.add_task("İlaç", at(1, 9), 0)
    b = h.store.add_task("Toplantı", at(1, 10), 0)
    h.script(resp(calls=[call("delete_task", task_id="t:" + a["id"][:6]), call("delete_task", task_id="t:" + b["id"][:6])]))
    out = h.ask("ikisini de sil", v2=True)
    assert "İlaç" in out["reply"] and "Toplantı" in out["reply"] and " ve " in out["reply"]
    out = h.ask("evet", v2=True)
    assert h.store.list_tasks() == []
    assert [x["type"] for x in out["actions"]] == ["cancel_reminder", "cancel_reminder"]


def test_injected_phone_number_is_dropped(h):
    h.script(resp(calls=[("call_contact", {"name": "Okay", "phone_number": "09001234567", "say": "Arıyorum."})]))
    out = h.ask("okayı ara", v2=True)
    assert out["actions"][0]["number"] == ""
    h.script(resp(calls=[("call_contact", {"name": "", "phone_number": "05321234567", "say": "Arıyorum."})]))
    out = h.ask("0532 123 45 67 numarasını ara", v2=True)
    assert out["actions"][0]["number"] == "05321234567"


def test_v1_turn_stops_before_the_old_app_times_out(h, monkeypatch):
    import time as time_module
    clock = {"t": 1000.0}
    monkeypatch.setattr(time_module, "monotonic", lambda: clock["t"])

    def slow_first_round(kwargs):
        clock["t"] += 8.0  # the first model call took 8 s
        return resp(calls=[call("add_task", text="Su iç", due_at=at(1, 9), repeat="none")])
    h.script(slow_first_round)  # no second step: a second call would fail the test
    out = h.ask("yarın 9'da su içmemi hatırlat")
    assert len(h.calls) == 1
    assert out["action"] == "add_task" and "Su iç" in out["reply"]
    assert h.calls[0]["timeout"] <= 9.0


def test_context_lists_upcoming_first_and_hides_old_one_shots(h):
    for i in range(5):
        h.store.add_task(f"Eski {i}", at(-10 + i, 9), 0)
    h.store.add_task("Yarınki", at(1, 9), 0)
    h.script(resp("ok"))
    h.ask("hatırlatmalarım neler")
    developer = next(i["content"] for i in h.calls[0]["input"] if i.get("role") == "developer")
    upcoming = developer.split("Bekleyen görevler")[1].split("Zamanı geçmiş")[0]
    assert "Yarınki" in upcoming and "Eski" not in upcoming
    assert "Zamanı geçmiş, çalmış tek seferlik hatırlatmalar (5" in developer


def test_client_note_reaches_the_model(h):
    h.script(resp(calls=[("call_contact", {"name": "Okay Demir", "phone_number": None, "say": "Okay Demir'i arıyorum."})]))
    r = h.client.post("/ask", json={"message": "Demir olanı", "client": {"version": 2, "capabilities": ["call"]},
                                     "context": {"client_note": "Rehberde birden fazla Okay var: Okay Demir, Okay Yılmaz. Hangisi olduğunu söyler misiniz?"}})
    developer = next(i["content"] for i in h.calls[0]["input"] if i.get("role") == "developer")
    assert "Telefonun son notu" in developer and "Okay Yılmaz" in developer
    assert r.get_json()["actions"][0]["name"] == "Okay Demir"


# ------------------------------------------------- review r2 regressions

def test_iptal_et_to_a_delete_question_does_not_delete(h):
    task, _ = _delete_question(h)
    h.script(resp(calls=[call("confirm_pending", answer="no")]), resp("Tamam, silmiyorum."))
    out = h.ask("iptal et")
    assert len(h.calls) == 3  # question + two model rounds for "iptal et"
    assert h.store.get_task(task["id"]) is not None


def test_legacy_refusal_is_told_even_when_out_of_time(h, monkeypatch):
    import time as time_module
    clock = {"t": 1000.0}
    monkeypatch.setattr(time_module, "monotonic", lambda: clock["t"])

    def slow(kwargs):
        clock["t"] += 7.0
        return resp(calls=[call("add_task", text="İlaç", due_at=at(1, 9), repeat="none"),
                           call("add_task", text="Okay'ı ara", due_at=at(1, 10), repeat="none")])
    h.script(slow)
    out = h.ask("yarın 9'da ilacımı 10'da okayı aramamı hatırlat")
    assert "ayrıca söyleyin" in out["reply"]


def test_editing_daily_time_to_earlier_today_moves_to_tomorrow(h):
    task = h.store.add_task("İlaç", at(1, 23, 59), DAILY_MS)
    h.script(resp(calls=[call("edit_task", task_id="t:" + task["id"][:6], text=None, due_at=at(0, 0, 1))]), resp("Tamam."))
    h.ask("ilacı gece 00:01'e çek")
    assert h.tool_output(1)["ok"] is True
    due = datetime.fromisoformat(h.store.get_task(task["id"])["due_at"])
    assert due > datetime.now(TZ)
