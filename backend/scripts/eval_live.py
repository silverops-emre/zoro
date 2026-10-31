"""Live check of the brain against the real OpenAI API, with a hard budget.

Uses a throw-away database seeded with SYNTHETIC data only (no client data
leaves the machine). Prints one line per scenario and a token/cost summary.

    OPENAI_API_KEY=... python scripts/eval_live.py --model gpt-5-mini --effort low
"""

from __future__ import annotations

import argparse
import json
import sys
import tempfile
import time
from dataclasses import replace
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from openai import OpenAI  # noqa: E402

from zoro.config import load_config  # noqa: E402
from zoro.web import create_app  # noqa: E402

TZ = ZoneInfo("Europe/Istanbul")
V2 = {"version": 2, "capabilities": ["calendar", "navigate", "call", "message", "open_app", "alarm", "reminders"]}

# USD per 1M tokens (input, output) — for the estimate only.
PRICES = {"gpt-5-mini": (0.25, 2.0), "gpt-4o-mini": (0.15, 0.60), "gpt-5.4-nano": (0.05, 0.40)}


class BudgetExceeded(Exception):
    pass


class MeteredClient:
    def __init__(self, inner: OpenAI, max_calls: int, max_tokens: int):
        self.inner, self.max_calls, self.max_tokens = inner, max_calls, max_tokens
        self.calls = self.input_tokens = self.output_tokens = 0
        self.last_tools: list[str] = []
        self.responses = self

    def create(self, **kwargs):
        if self.calls >= self.max_calls or self.input_tokens + self.output_tokens >= self.max_tokens:
            raise BudgetExceeded(f"budget hit: {self.calls} calls, {self.input_tokens + self.output_tokens} tokens")
        self.calls += 1
        response = self.inner.responses.create(**kwargs)
        usage = getattr(response, "usage", None)
        if usage is not None:
            self.input_tokens += usage.input_tokens or 0
            self.output_tokens += usage.output_tokens or 0
        self.last_tools += [i.name for i in response.output if i.type == "function_call"]
        return response


def at(days: int, hour: int, minute: int = 0) -> datetime:
    return (datetime.now(TZ) + timedelta(days=days)).replace(hour=hour, minute=minute, second=0, microsecond=0)


def ms(dt: datetime) -> int:
    return int(dt.timestamp() * 1000)


def next_weekday(weekday: int) -> datetime:
    today = datetime.now(TZ)
    return today + timedelta(days=(weekday - today.weekday()) % 7 or 7)


CALENDAR = [
    {"id": "zoro_1", "title": "Silivri toplantısı", "start_ms": ms(at(1, 14)), "end_ms": ms(at(1, 15)),
     "location": "Silivri", "source": "ZORO", "all_day": False},
    {"id": "android_7_1", "title": "Aile yemeği", "start_ms": ms(at(2, 19)), "end_ms": ms(at(2, 21)),
     "location": "", "source": "ANDROID:Google", "all_day": False},
]

# (message, v2, expect_tools, forbid_tools, check(response) -> str|None)
def friday_15(r):
    acts = [a for a in r["actions"] if a["type"] == "calendar_add"]
    if not acts:
        return "no calendar_add action"
    start = datetime.fromtimestamp(acts[0]["start_ms"] / 1000, TZ)
    target = next_weekday(4)
    return None if (start.date(), start.hour) == (target.date(), 15) else f"wrong time {start}"


def due_check(days, hour, daily=False):
    def check(r):
        if r.get("action") != "add_task":
            return f"action={r.get('action')!r}"
        due = datetime.fromisoformat(r["due_at"])
        want = at(days, hour)
        if daily and due.hour == hour:
            return None
        return None if (due.date(), due.hour) == (want.date(), want.hour) else f"due {due}"
    return check


SCENARIOS = [
    ("Beni İstanbul'a götür", True, {"navigate"}, set(), lambda r: None if any(a["type"] == "navigate" for a in r["actions"]) else "no navigate action"),
    ("Toplantım ne zaman?", True, set(), {"calendar_delete", "calendar_update", "remember"}, lambda r: None if "14" in r["reply"] else "time not in reply"),
    ("Silivri'deki toplantıyı söyle", True, set(), {"calendar_delete", "delete_task"}, None),
    ("Yarın sabah 9'da Okay'ı aramamı hatırlat", True, {"add_task"}, set(), due_check(1, 9)),
    ("Okay'ı ara", True, {"call_contact"}, set(), None),
    ("Okay'a yarın geliyorum diye WhatsApp'tan yaz", True, {"send_message"}, set(), None),
    ("Bekle biraz", True, set(), {"add_task", "calendar_add"}, None),
    ("Cuma günü saat 3'te Ahmet Bey'le görüşmemi takvime ekle", True, {"calendar_add"}, set(), friday_15),
    ("Her gün akşam 8'de ilacımı hatırlat", True, {"add_task"}, set(), due_check(0, 20, daily=True)),
    ("Bugün İstanbul'da hava nasıl?", True, {"get_weather"}, set(), None),
    ("Dolar kaç lira?", True, {"get_exchange_rates"}, set(), None),
    ("Arabamın plakası 34 ZY 1234, bunu unutma", True, {"remember"}, set(), None),
    ("Plakam neydi?", True, set(), {"remember"}, lambda r: None if "1234" in r["reply"].replace(" ", "") or "1234" in r["reply"] else "plate not in reply"),
    ("Yarın 7'de beni uyandır", True, {"set_alarm"}, set(), None),
    ("YouTube'da Barış Manço aç", True, {"open_app"}, set(), None),
    ("Silivri toplantısını iptal et", True, {"calendar_delete"}, set(), lambda r: None if r["pending"] else "no confirmation asked"),
    ("evet", True, set(), set(), lambda r: None if any(a["type"] == "calendar_delete" for a in r["actions"]) else "delete not executed"),
    ("Beni İstanbul'a götür", False, set(), {"navigate"}, lambda r: None if not r["actions"] else "v1 got phone action"),
]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="gpt-5-mini")
    parser.add_argument("--effort", default=None)
    parser.add_argument("--max-calls", type=int, default=45)
    parser.add_argument("--max-tokens", type=int, default=250_000)
    parser.add_argument("--only", type=int, nargs="*")
    parser.add_argument("--out", default=None)
    args = parser.parse_args()

    metered = MeteredClient(OpenAI(max_retries=1), args.max_calls, args.max_tokens)
    tmp = Path(tempfile.mkdtemp(prefix="zoro-eval-"))
    cfg = replace(load_config(tmp), db_path=tmp / "zoro.db", model=args.model, reasoning_effort=args.effort)
    app = create_app(cfg, openai_factory=lambda: metered)
    client = app.test_client()
    store = app.extensions["zoro"]["store"]
    store.add_task("Su iç", at(1, 10).isoformat(), 0)

    rows, failures = [], 0
    for index, (message, v2, expect, forbid, check) in enumerate(SCENARIOS, 1):
        if args.only and index not in args.only:
            continue
        metered.last_tools = []
        body = {"message": message}
        if v2:
            body["client"] = V2
            body["context"] = {"tz": "Europe/Istanbul", "calendar": CALENDAR}
        started = time.monotonic()
        try:
            r = client.post("/ask", json=body).get_json()
        except BudgetExceeded as error:
            print(f"STOP: {error}")
            break
        took = time.monotonic() - started
        tools = set(metered.last_tools)
        problems = []
        if r.get("error"):
            problems.append(f"error={r['error']}")
        if not expect <= tools:
            problems.append(f"missing {sorted(expect - tools)}")
        if tools & forbid:
            problems.append(f"forbidden {sorted(tools & forbid)}")
        if check and not r.get("error"):
            issue = check(r)
            if issue:
                problems.append(issue)
        status = "PASS" if not problems else "FAIL"
        failures += bool(problems)
        row = {"n": index, "status": status, "message": message, "v2": v2, "tools": sorted(tools),
               "actions": [a["type"] for a in r.get("actions", [])], "reply": r.get("reply"),
               "seconds": round(took, 1), "problems": problems}
        rows.append(row)
        print(f"{index:2d} {status} {took:4.1f}s {message!r} tools={sorted(tools)} -> {r.get('reply')!r} {problems or ''}")

    pin, pout = PRICES.get(args.model, (0, 0))
    cost = metered.input_tokens / 1e6 * pin + metered.output_tokens / 1e6 * pout
    summary = {"model": args.model, "effort": args.effort, "calls": metered.calls,
               "input_tokens": metered.input_tokens, "output_tokens": metered.output_tokens,
               "estimated_usd": round(cost, 4), "passed": len(rows) - failures, "total": len(rows)}
    print(json.dumps(summary, ensure_ascii=False))
    if args.out:
        Path(args.out).write_text(json.dumps({"summary": summary, "rows": rows}, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
