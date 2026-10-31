import itertools
import json
import sys
from dataclasses import replace
from datetime import datetime, timedelta
from pathlib import Path
from types import SimpleNamespace
from zoneinfo import ZoneInfo

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from zoro.config import load_config  # noqa: E402
from zoro.web import create_app  # noqa: E402

TZ = ZoneInfo("Europe/Istanbul")
_ids = itertools.count(1)


def now():
    return datetime.now(TZ)


def at(days=0, hour=9, minute=0):
    """ISO time `days` from today at hour:minute (Istanbul)."""
    base = now() + timedelta(days=days)
    return base.replace(hour=hour, minute=minute, second=0, microsecond=0).isoformat()


def ms(iso_value):
    return int(datetime.fromisoformat(iso_value).timestamp() * 1000)


def call(name, **arguments):
    return (name, arguments)


def resp(text=None, calls=()):
    output = []
    for name, arguments in calls:
        output.append(SimpleNamespace(
            type="function_call", name=name, call_id=f"call_{next(_ids)}",
            arguments=arguments if isinstance(arguments, str) else json.dumps(arguments, ensure_ascii=False),
        ))
    if text:
        output.append(SimpleNamespace(type="message", content=[SimpleNamespace(type="output_text", text=text)]))
    return SimpleNamespace(id=f"resp_{next(_ids)}", output=output, output_text=text or "")


class FakeResponses:
    def __init__(self):
        self.script = []
        self.calls = []

    def create(self, **kwargs):
        self.calls.append(kwargs)
        if not self.script:
            raise AssertionError("model called more times than scripted")
        step = self.script.pop(0)
        if isinstance(step, Exception):
            raise step
        return step(kwargs) if callable(step) else step


class FakeOpenAI:
    def __init__(self):
        self.responses = FakeResponses()


V2_CLIENT = {"version": 2, "capabilities": ["calendar", "navigate", "call", "message", "open_app", "alarm", "reminders"]}


class Harness:
    def __init__(self, tmp_path, **cfg_overrides):
        cfg = load_config(tmp_path)
        cfg = replace(cfg, db_path=tmp_path / "zoro.db", **cfg_overrides)
        self.cfg = cfg
        self.fake = FakeOpenAI()
        self.app = create_app(cfg, openai_factory=lambda: self.fake)
        self.client = self.app.test_client()
        self.store = self.app.extensions["zoro"]["store"]

    def script(self, *steps):
        self.fake.responses.script.extend(steps)

    @property
    def calls(self):
        return self.fake.responses.calls

    def ask(self, message, v2=False, calendar=None, headers=None, status=200):
        body = {"message": message}
        if v2:
            body["client"] = V2_CLIENT
            body["context"] = {"tz": "Europe/Istanbul", "calendar": calendar or []}
        r = self.client.post("/ask", json=body, headers=headers or {})
        assert r.status_code == status, r.get_data(as_text=True)
        return r.get_json()

    def tool_output(self, call_index, position=0):
        """function_call_output sent back to the model in calls[call_index]."""
        items = self.calls[call_index]["input"]
        outputs = [i for i in items if isinstance(i, dict) and i.get("type") == "function_call_output"]
        return json.loads(outputs[position]["output"])

    def tool_names(self, call_index=0):
        return {t.get("name") for t in self.calls[call_index]["tools"]}


@pytest.fixture
def h(tmp_path):
    return Harness(tmp_path)
