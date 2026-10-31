"""2.1: metering, settings, budget, web search, natural voice, call log, writable phone calendar."""

import base64
import json
from datetime import datetime, timedelta
from types import SimpleNamespace

import pytest

from conftest import TZ, V2_CLIENT, Harness, at, call, ms, now, resp
from zoro import costs, pricing, settings as user_settings


def usage(input_tokens=1000, cached=0, output=100):
    return SimpleNamespace(input_tokens=input_tokens, output_tokens=output,
                           input_tokens_details=SimpleNamespace(cached_tokens=cached))


def metered(text=None, calls=(), searches=0, **tokens):
    r = resp(text, calls)
    r.output = [SimpleNamespace(type="web_search_call", status="completed") for _ in range(searches)] + r.output
    r.usage = usage(**tokens)
    return r


@pytest.fixture(autouse=True)
def no_network(monkeypatch):
    monkeypatch.setattr("zoro.web.info.exchange_rates",
                        lambda codes=None: {"ok": True, "kurlar": {"USD": {"doviz_satis": 42.0}}})


def ask_v2(h, message, context=None):
    body = {"message": message, "client": V2_CLIENT, "context": {"tz": "Europe/Istanbul", **(context or {})}}
    r = h.client.post("/ask", json=body)
    assert r.status_code == 200, r.get_data(as_text=True)
    return r.get_json()


# ------------------------------------------------------------ pricing

def test_prices_match_the_official_table_and_snapshots_resolve():
    assert pricing.text_cost("gpt-6-luna", 1_000_000, 0, 1_000_000) == pytest.approx(0.60)
    assert pricing.text_cost("gpt-6-luna", 1_000_000, 1_000_000, 0) == pytest.approx(0.01)
    assert pricing.text_cost("gpt-6-luna-2026-09-01", 1_000_000, 0, 0) == pytest.approx(0.10)
    assert pricing.text_cost("gpt-6-lunatic", 1, 0, 0) is None
    assert pricing.speech_cost("gpt-4o-mini-tts", 51, 204) == pytest.approx((51 * 0.6 + 204 * 12) / 1e6)


def test_price_override_from_env(monkeypatch):
    monkeypatch.setenv("ZORO_PRICE_OVERRIDES", json.dumps({"my-model": [1, 0.5, 2]}))
    assert pricing.text_cost("my-model", 1_000_000, 0, 1_000_000) == pytest.approx(3.0)


# ------------------------------------------------------------ metering

def test_every_model_call_is_metered_with_cost(h):
    h.script(metered("Merhaba.", input_tokens=4000, cached=1000, output=200))
    ask_v2(h, "selam")
    rows = h.store.usage_rows(now().strftime("%Y-%m"))
    assert len(rows) == 1
    row = rows[0]
    assert row["feature"] == "beyin" and row["model"] == "gpt-6-luna"
    assert row["cost_usd"] == pytest.approx((3000 * 0.10 + 1000 * 0.01 + 200 * 0.50) / 1e6)


def test_two_rounds_share_one_turn_id(h):
    h.script(metered(calls=[call("get_weather", city="İstanbul")]), metered("Hava güzel."))
    h.app.extensions["zoro"]  # noqa: B018
    import zoro.tools as tools
    original = tools.info.weather
    tools.info.weather = lambda city, tz="Europe/Istanbul": {"ok": True, "yer": city}
    try:
        ask_v2(h, "hava nasıl")
    finally:
        tools.info.weather = original
    rows = h.store.usage_rows(now().strftime("%Y-%m"))
    assert len(rows) == 2 and rows[0]["turn_id"] == rows[1]["turn_id"]


def test_unknown_model_price_is_recorded_as_null(tmp_path):
    h = Harness(tmp_path, model="mystery-model")
    h.script(metered("Tamam."))
    ask_v2(h, "selam")
    assert h.store.usage_rows(now().strftime("%Y-%m"))[0]["cost_usd"] is None
    assert h.client.get("/usage").get_json()["unknown_price"] is True


def test_response_without_usage_is_not_an_error(h):
    h.script(resp("Merhaba."))
    assert ask_v2(h, "selam")["reply"] == "Merhaba."
    assert h.store.usage_rows(now().strftime("%Y-%m")) == []


# ------------------------------------------------------- web search

def test_web_search_off_by_default(h):
    h.script(metered("Bilmiyorum."))
    ask_v2(h, "bugün haberlerde ne var")
    assert not any(t.get("type") == "web_search" for t in h.calls[0]["tools"])
    assert "web aramasını" not in h.calls[0]["instructions"]


def test_web_search_switch_adds_tool_and_bills_searches(h):
    assert h.client.put("/settings", json={"web_search": True}).status_code == 200
    h.script(metered("Bugün maç 2-1 bitti.", searches=2, input_tokens=9000, output=150))
    ask_v2(h, "galatasaray maçı kaç kaç bitti")
    tool = next(t for t in h.calls[0]["tools"] if t.get("type") == "web_search")
    assert tool["user_location"]["country"] == "TR"
    row = h.store.usage_rows(now().strftime("%Y-%m"))[0]
    assert row["feature"] == "web_arama" and row["searches"] == 2
    assert row["cost_usd"] == pytest.approx(0.02 + (9000 * 0.10 + 150 * 0.50) / 1e6)


def test_budget_reached_turns_paid_extras_off_but_brain_keeps_working(h):
    h.client.put("/settings", json={"web_search": True, "natural_voice": True, "monthly_budget_usd": 1})
    h.store.add_usage(created_at=now(), feature="beyin", model="gpt-6-luna", cost_usd=1.5)
    h.script(metered("Merhaba."))
    assert ask_v2(h, "selam")["reply"] == "Merhaba."
    assert not any(t.get("type") == "web_search" for t in h.calls[0]["tools"])
    assert h.client.post("/tts", json={"text": "Merhaba."}).status_code == 402
    summary = h.client.get("/usage").get_json()
    assert summary["budget_reached"] is True


def test_zero_budget_means_no_limit(h):
    h.client.put("/settings", json={"web_search": True, "monthly_budget_usd": 0})
    h.store.add_usage(created_at=now(), feature="beyin", model="gpt-6-luna", cost_usd=500)
    h.script(metered("Tamam."))
    ask_v2(h, "haber var mı")
    assert any(t.get("type") == "web_search" for t in h.calls[0]["tools"])


# ------------------------------------------------------------ settings

def test_settings_defaults_and_validation(h):
    data = h.client.get("/settings").get_json()
    assert data["settings"] == {"web_search": False, "natural_voice": False, "voice": "marin",
                                "monthly_budget_usd": 10.0, "briefing_enabled": True, "briefing_time": "10:00"}
    assert "cedar" in data["voices"]
    bad = h.client.put("/settings", json={"voice": "darth", "monthly_budget_usd": -3, "briefing_time": "25:00",
                                          "web_search": "yes"})
    assert bad.status_code == 400 and len(bad.get_json()["errors"]) == 4
    assert h.client.get("/settings").get_json()["settings"]["voice"] == "marin"  # nothing half-saved
    ok = h.client.put("/settings", json={"voice": "cedar", "briefing_time": "07:30", "unknown": 1})
    assert ok.get_json()["settings"]["voice"] == "cedar"
    assert ok.get_json()["settings"]["briefing_time"] == "07:30"
    assert "unknown" not in ok.get_json()["settings"]


def test_settings_are_shared_across_app_instances(tmp_path):
    a = Harness(tmp_path)
    a.client.put("/settings", json={"natural_voice": True})
    b = Harness(tmp_path)  # second gunicorn worker on the same database
    assert b.client.get("/settings").get_json()["settings"]["natural_voice"] is True


def test_settings_need_the_api_key(tmp_path):
    h = Harness(tmp_path, api_key="k")
    assert h.client.get("/settings").status_code == 401
    assert h.client.get("/usage").status_code == 401
    assert h.client.post("/tts", json={"text": "x"}).status_code == 401


# --------------------------------------------------------------- usage

def test_usage_summary_without_data_is_honest(h):
    s = h.client.get("/usage").get_json()
    assert s["total_usd"] == 0 and s["little_data"] is True
    assert s["asks_per_day_assumed"] is True
    brain = next(f for f in s["features"] if f["key"] == "beyin")
    assert brain["per_use_usd"] is None and brain["basis"] == "veri_yok"
    voice = next(f for f in s["features"] if f["key"] == "dogal_ses")
    assert voice["enabled"] is False and voice["projected_usd"] is None
    assert voice["monthly_if_on_usd"] == pytest.approx(
        round(pricing.speech_cost("gpt-4o-mini-tts", 51, 204) * 20 * 30, 2))
    assert s["usd_try"] == 42.0 and s["tts_shutdown"] == "2027-01-06"
    assert s["prices_source"].startswith("https://developers.openai.com")


def test_usage_projection_from_measured_data(h):
    t = now()
    for days_ago in (3, 2, 1):
        for i in range(10):
            h.store.add_usage(created_at=t - timedelta(days=days_ago), feature="beyin", model="gpt-6-luna",
                              cost_usd=0.001, turn_id=f"{days_ago}-{i}")
    s = costs.summary(h.store, user_settings.load(h.store, h.cfg), "gpt-6-luna", "gpt-4o-mini-tts", t,
                      lambda: None)
    if t.day <= 3:
        pytest.skip("rows fall into the previous month on the first days of a month")
    brain = next(f for f in s["features"] if f["key"] == "beyin")
    assert brain["uses"] == 30 and brain["cost_usd"] == pytest.approx(0.03)
    assert brain["per_use_usd"] == pytest.approx(0.001)
    assert brain["basis"] == "olculen"
    assert s["asks_per_day"] == pytest.approx(10.0, abs=0.2)
    assert s["projected_usd"] >= s["total_usd"]
    assert s["usd_try"] is None


def test_usd_try_cached_per_day(h):
    calls = []
    t = now()
    assert costs.usd_try_rate(h.store, t, lambda: calls.append(1) or 40.0) == 40.0
    assert costs.usd_try_rate(h.store, t, lambda: calls.append(1) or 41.0) == 40.0
    assert len(calls) == 1
    # Next day the fetch fails: yesterday's rate is used.
    assert costs.usd_try_rate(h.store, t + timedelta(days=1), lambda: None) == 40.0


# --------------------------------------------------------- natural voice

class FakeSpeech:
    def __init__(self, chunks, fail=False):
        self.chunks, self.fail, self.calls = chunks, fail, []

    def create(self, **kwargs):
        self.calls.append(kwargs)
        if self.fail:
            raise RuntimeError("model gone")
        lines = [f"data: {json.dumps({'type': 'speech.audio.delta', 'audio': base64.b64encode(c).decode()})}"
                 for c in self.chunks]
        lines.append("data: " + json.dumps({"type": "speech.audio.done",
                                            "usage": {"input_tokens": 50, "output_tokens": 200}}))
        lines.append("data: [DONE]")

        class Ctx:
            def __enter__(self_inner):
                return SimpleNamespace(iter_lines=lambda: iter(lines))

            def __exit__(self_inner, *exc):
                return False
        return Ctx()


def with_speech(h, speech):
    h.fake.audio = SimpleNamespace(speech=SimpleNamespace(with_streaming_response=speech))
    return speech


def test_tts_disabled_by_default_but_preview_allowed(h):
    speech = with_speech(h, FakeSpeech([b"\x01\x02"]))
    assert h.client.post("/tts", json={"text": "Merhaba."}).status_code == 409
    r = h.client.post("/tts", json={"text": "Merhaba.", "preview": True, "voice": "cedar"})
    assert r.status_code == 200 and r.data == b"\x01\x02"
    assert speech.calls[0]["voice"] == "cedar" and speech.calls[0]["response_format"] == "pcm"


def test_tts_streams_pcm_and_meters_it(h):
    h.client.put("/settings", json={"natural_voice": True, "voice": "sage"})
    speech = with_speech(h, FakeSpeech([b"ab", b"cd", b"ef"]))
    r = h.client.post("/tts", json={"text": "Yarın 09:00'da toplantınız var."})
    assert r.status_code == 200
    assert r.data == b"abcdef"
    assert r.headers["X-Zoro-Audio"] == "pcm_s16le;rate=24000;channels=1"
    assert speech.calls[0]["model"] == "gpt-4o-mini-tts" and speech.calls[0]["voice"] == "sage"
    rows = h.store.usage_rows(now().strftime("%Y-%m"))
    assert rows[0]["feature"] == "dogal_ses"
    assert rows[0]["cost_usd"] == pytest.approx((50 * 0.6 + 200 * 12) / 1e6)


def test_tts_upstream_failure_is_502_so_the_phone_falls_back(h):
    h.client.put("/settings", json={"natural_voice": True})
    with_speech(h, FakeSpeech([], fail=True))
    r = h.client.post("/tts", json={"text": "Merhaba."})
    assert r.status_code == 502 and r.get_json()["error"] == "voice_unavailable"


def test_tts_rejects_empty_and_huge_text(h):
    h.client.put("/settings", json={"natural_voice": True})
    assert h.client.post("/tts", json={"text": "  "}).status_code == 400
    assert h.client.post("/tts", json={"text": "a" * 1300}).status_code == 400


# ------------------------------------------------------------ call log

def calls_context(*entries):
    return {"recent_calls": [dict(e) for e in entries]}


def test_recent_calls_reach_the_model_newest_first_and_old_ones_dropped(h):
    t = now()
    h.script(metered("Ahmet Bey aradı."))
    ask_v2(h, "beni kim aradı", calls_context(
        {"name": "Ahmet Yılmaz", "number": "05321112233", "type": "missed", "time_ms": ms((t - timedelta(hours=2)).isoformat())},
        {"name": "", "number": "02125556677", "type": "incoming", "time_ms": ms((t - timedelta(hours=1)).isoformat())},
        {"name": "Eski", "number": "05000000000", "type": "missed", "time_ms": ms((t - timedelta(days=3)).isoformat())},
        {"name": "Bozuk", "number": "", "type": "missed", "time_ms": ms(t.isoformat())},
    ))
    block = h.calls[0]["input"][-2]["content"]
    assert "[c1] kayıtlı olmayan numara, sonu 6677, gelen" in block
    assert "[c2] Ahmet Yılmaz, cevapsız" in block
    assert "Eski" not in block and "Bozuk" not in block
    assert "05321112233" not in block  # numbers stay on the phone side of the prompt


def test_call_back_uses_the_call_log_number(h):
    t = now()
    h.script(metered(calls=[("call_contact", {"name": "", "phone_number": None, "call_ref": "c1", "say": "Arıyorum."})]))
    out = ask_v2(h, "onu geri ara", calls_context(
        {"name": "", "number": "05321112233", "type": "missed", "time_ms": ms(t.isoformat())}))
    assert out["actions"] == [{"type": "call", "name": "", "number": "05321112233", "from_call_log": True}]


def test_call_back_with_unknown_ref_fails(h):
    h.script(metered(calls=[("call_contact", {"name": "", "phone_number": None, "call_ref": "c9", "say": "Arıyorum."})]),
             metered("Bu aramayı bulamadım."))
    out = ask_v2(h, "onu geri ara", calls_context())
    assert out["actions"] == []
    assert h.tool_output(1)["ok"] is False


def test_no_call_log_sent_means_no_section(h):
    h.script(metered("Bilmiyorum."))
    ask_v2(h, "beni kim aradı")
    assert "Son aramalar" not in h.calls[0]["input"][-2]["content"]


# ------------------------------------------------- writable phone calendar

def phone_event(writable, start_iso):
    return {"id": "android_42_" + str(ms(start_iso)), "title": "Kurul toplantısı", "start_ms": ms(start_iso),
            "end_ms": ms(start_iso) + 3600_000, "location": "", "source": "ANDROID:Google", "writable": writable}


def test_writable_phone_event_can_be_moved(h):
    start = at(2, 10)
    h.script(metered(calls=[call("calendar_update", event_id="e1", title=None, start=at(2, 15), end=None,
                                 location=None, say="Toplantıyı 15:00'e aldım.")]))
    out = ask_v2(h, "kurul toplantısını 3'e al", {"calendar": [phone_event(True, start)]})
    assert out["actions"][0]["type"] == "calendar_update"
    assert out["actions"][0]["event_id"].startswith("android_42_")
    assert "(telefon takvimi)" in h.calls[0]["input"][-2]["content"]


def test_read_only_phone_event_is_refused(h):
    start = at(2, 10)
    h.script(metered(calls=[call("calendar_delete", event_id="e1")]), metered("Bu etkinliği silemem."))
    out = ask_v2(h, "kurul toplantısını sil", {"calendar": [phone_event(False, start)]})
    assert out["pending"] is None and out["actions"] == []
    assert "Zoro silemez" in h.tool_output(1)["error"]


def test_only_a_literal_true_marks_an_event_writable(h):
    start = at(2, 10)
    event = phone_event("true", start)
    h.script(metered("Tamam."))
    ask_v2(h, "takvimde ne var", {"calendar": [event]})
    assert "Zoro değiştiremez" in h.calls[0]["input"][-2]["content"]


# --------------------------------------------------------------- briefing

def test_briefing_is_its_own_cost_bucket_and_mentions_calls_and_news(h):
    h.client.put("/settings", json={"web_search": True})
    h.script(metered("Günaydın."))
    t = now()
    r = h.client.post("/briefing", json={"client": V2_CLIENT, "context": {
        "calendar": [], **calls_context({"name": "Ahmet", "number": "0532", "type": "missed",
                                         "time_ms": ms(t.isoformat())})}})
    assert r.get_json()["reply"] == "Günaydın."
    message = h.calls[0]["input"][-1]["content"]
    assert "cevapsız arama" in message and "haber" in message
    assert h.store.usage_rows(now().strftime("%Y-%m"))[0]["feature"] == "sabah_ozeti"


def test_version_advertises_new_features(h):
    assert "tts" in h.client.get("/version").get_json()["features"]


def test_web_citations_are_not_read_aloud(h):
    h.script(metered("Haber şu: Zelenskiy İstanbul'da. ([kyivindependent.com](https://kyivindependent.com/x?utm_source=openai)) "
                     "Ayrıntı için [buraya](https://a.b/c) bakın: https://example.com/y"))
    reply = ask_v2(h, "haber var mı")["reply"]
    assert "http" not in reply and "](" not in reply and "kyivindependent" not in reply
    assert reply.startswith("Haber şu: Zelenskiy İstanbul'da.")


# ------------------------------------------------- review r3 regressions

def test_projection_keeps_spend_of_switched_off_features_and_uses_own_span(h):
    t = now()
    if t.day < 16:
        pytest.skip("needs 15 days of this month behind us")
    start = t.replace(day=1, hour=9, minute=0, second=0, microsecond=0)
    for d in range(10):  # voice on days 1-10, then switched off
        h.store.add_usage(created_at=start + timedelta(days=d), feature="dogal_ses", model="gpt-4o-mini-tts",
                          cost_usd=0.3)
    for d in range(t.day - 1):
        h.store.add_usage(created_at=start + timedelta(days=d), feature="beyin", model="gpt-6-luna",
                          cost_usd=0.01, turn_id=f"b{d}")
    for i in range(20):  # web search switched on only today
        h.store.add_usage(created_at=t - timedelta(minutes=30 - i), feature="web_arama", model="gpt-6-luna",
                          searches=1, cost_usd=0.01)
    h.client.put("/settings", json={"natural_voice": False, "web_search": True, "monthly_budget_usd": 0})
    s = h.client.get("/usage").get_json()
    assert s["projected_usd"] >= s["total_usd"]
    web = next(f for f in s["features"] if f["key"] == "web_arama")
    assert web["projected_usd"] > 0.2 * 1  # 20 searches in one day keep going, not spread over the month


def test_budget_reached_stops_projection_of_paid_extras(h):
    h.client.put("/settings", json={"natural_voice": True, "monthly_budget_usd": 1})
    h.store.add_usage(created_at=now() - timedelta(hours=30), feature="dogal_ses", model="gpt-4o-mini-tts",
                      cost_usd=1.2)
    s = h.client.get("/usage").get_json()
    voice = next(f for f in s["features"] if f["key"] == "dogal_ses")
    assert s["budget_reached"] is True and voice["projected_usd"] is None
    assert s["projected_usd"] == pytest.approx(1.2)


def test_call_back_needs_the_users_own_request(h):
    t = now()
    ctx = calls_context({"name": "", "number": "08821234567", "type": "missed", "time_ms": ms(t.isoformat())})
    h.script(metered(calls=[("call_contact", {"name": "", "phone_number": None, "call_ref": "c1", "say": "Arıyorum."})]),
             metered("Takviminizde bir not var."))
    out = ask_v2(h, "takvimde ne var", ctx)  # injected calendar text, no call request
    assert out["actions"] == [] and h.tool_output(1)["ok"] is False


def test_briefing_has_read_only_tools_and_cannot_call(h):
    h.script(metered(calls=[("call_contact", {"name": "", "phone_number": None, "call_ref": "c1", "say": "x"})]),
             metered("Günaydın."))
    t = now()
    r = h.client.post("/briefing", json={"client": V2_CLIENT, "context": {
        **calls_context({"name": "", "number": "0882", "type": "missed", "time_ms": ms(t.isoformat())})}})
    names = {tool.get("name") for tool in h.calls[0]["tools"]}
    assert names <= {"get_weather", "get_exchange_rates", None}
    assert r.get_json()["actions"] == [] and h.tool_output(1)["ok"] is False


def test_briefing_ai_failure_is_503_without_rules_or_history(h):
    h.script(RuntimeError("down"))
    before = h.store.count_messages()
    r = h.client.post("/briefing", json={"client": V2_CLIENT, "context": {"calendar": []}})
    assert r.status_code == 503 and r.get_json()["error"] == "ai_unavailable"
    assert h.store.count_messages() == before


def test_briefing_leaves_open_confirmation_alone(h):
    task = h.store.add_task("Su iç", at(1, 10), 0)
    h.script(metered(calls=[call("delete_task", task_id=task["id"][:6])]))
    assert ask_v2(h, "su içme görevini sil")["pending"] is not None
    h.script(metered("Günaydın."))
    h.client.post("/briefing", json={"client": V2_CLIENT, "context": {}})
    assert h.store.active_pending(now()) is not None


def test_tts_cut_short_is_still_metered(h):
    h.client.put("/settings", json={"natural_voice": True})

    class NoDone(FakeSpeech):
        def create(self, **kwargs):
            lines = [f"data: {json.dumps({'type': 'speech.audio.delta', 'audio': base64.b64encode(b'x' * 48000).decode()})}"]

            class Ctx:
                def __enter__(self_inner):
                    return SimpleNamespace(iter_lines=lambda: iter(lines))

                def __exit__(self_inner, *exc):
                    return False
            return Ctx()
    with_speech(h, NoDone([]))
    r = h.client.post("/tts", json={"text": "Merhaba Zafer Bey."})
    assert len(r.data) == 48000
    row = h.store.usage_rows(now().strftime("%Y-%m"))[0]
    assert row["feature"] == "dogal_ses" and row["output_tokens"] == 25  # 1 s of audio
    assert row["cost_usd"] > 0


def test_citations_with_parentheses_and_groups(h):
    from zoro.brain import _clean_reply
    assert _clean_reply("Galatasaray 2-1 kazandı. ([tr.wikipedia.org](https://tr.wikipedia.org/wiki/Galatasaray_(futbol_takımı)?utm_source=openai))") \
        == "Galatasaray 2-1 kazandı."
    assert _clean_reply("Doğa Eczanesi açık. ([eczaneler.gen.tr](https://e.gen.tr/a), [h.org](https://h.org/b))") \
        == "Doğa Eczanesi açık."


def test_tiny_positive_budget_is_rejected_not_rounded_to_unlimited(h):
    r = h.client.put("/settings", json={"monthly_budget_usd": 0.004})
    assert r.status_code == 400
    assert h.client.get("/settings").get_json()["settings"]["monthly_budget_usd"] == 10.0


def test_concurrent_setting_saves_do_not_undo_each_other(h):
    import threading
    keys = [f"k{i}" for i in range(20)]
    threads = [threading.Thread(target=lambda k=k: h.store.update_json_meta("t", lambda cur: {**cur, k: True}))
               for k in keys]
    for th in threads:
        th.start()
    for th in threads:
        th.join()
    assert set(json.loads(h.store.get_meta("t"))) == set(keys)


def test_calendar_move_sends_only_changed_fields(h):
    start = at(2, 10)
    h.script(metered(calls=[call("calendar_update", event_id="e1", title=None, start=at(2, 15), end=None,
                                 location=None, say="Taşıdım.")]))
    out = ask_v2(h, "kurul toplantısını 3'e al", {"calendar": [phone_event(True, start)]})
    action = out["actions"][0]
    assert "title" not in action and "location" not in action
