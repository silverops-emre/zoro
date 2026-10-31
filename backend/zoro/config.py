"""Runtime configuration, read once from the environment (.env next to app.py)."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


def _bool(value: str | None, default: bool = False) -> bool:
    if value is None or value.strip() == "":
        return default
    return value.strip().lower() in {"1", "true", "yes", "on", "evet"}


def _int(value: str | None, default: int) -> int:
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return default


@dataclass(frozen=True)
class Config:
    base_dir: Path
    db_path: Path
    # Legacy v1 JSON files, imported once into SQLite on first start.
    legacy_history_file: Path
    legacy_tasks_file: Path
    legacy_personal_file: Path

    model: str = "gpt-6-luna"
    reasoning_effort: str | None = None
    openai_timeout_s: float = 30.0
    max_tool_rounds: int = 4
    history_messages: int = 16
    web_search: bool = False

    # App-level key checked in the X-Zoro-Key header. Empty = disabled
    # (Caddy basic auth in front of the app still applies).
    api_key: str = ""
    timezone: str = "Europe/Istanbul"
    user_title: str = "Zafer Bey"
    user_full_name: str = "Zafer Yılmaz"
    home_city: str = "İstanbul"
    confirm_ttl_s: int = 180
    # Seconds a whole /ask turn may take. v1 app: OkHttp 10 s read timeout.
    # v2 app waits 60 s, but gunicorn kills a worker after --timeout (30 s default).
    turn_budget_v1_s: float = 9.0
    turn_budget_v2_s: float = 25.0
    max_message_chars: int = 2000
    trust_proxy: bool = True
    extra: dict = field(default_factory=dict)


def load_config(base_dir: Path | None = None) -> Config:
    base = Path(base_dir or os.environ.get("ZORO_BASE_DIR") or Path(__file__).resolve().parent.parent)
    data_dir = Path(os.environ.get("ZORO_DATA_DIR") or base)
    return Config(
        base_dir=base,
        db_path=Path(os.environ.get("ZORO_DB_PATH") or data_dir / "zoro.db"),
        legacy_history_file=data_dir / "zoro_memory.json",
        legacy_tasks_file=data_dir / "zoro_tasks.json",
        legacy_personal_file=data_dir / "zoro_personal_memory.json",
        model=os.environ.get("ZORO_MODEL", "gpt-6-luna").strip() or "gpt-6-luna",
        reasoning_effort=(os.environ.get("ZORO_REASONING_EFFORT") or "").strip() or None,
        openai_timeout_s=float(os.environ.get("ZORO_OPENAI_TIMEOUT", "30") or 30),
        max_tool_rounds=max(1, _int(os.environ.get("ZORO_MAX_TOOL_ROUNDS"), 4)),
        history_messages=max(0, _int(os.environ.get("ZORO_HISTORY_MESSAGES"), 16)),
        web_search=_bool(os.environ.get("ZORO_WEB_SEARCH"), False),
        api_key=(os.environ.get("ZORO_API_KEY") or "").strip(),
        timezone=os.environ.get("ZORO_TZ", "Europe/Istanbul"),
        user_title=os.environ.get("ZORO_USER_TITLE", "Zafer Bey"),
        user_full_name=os.environ.get("ZORO_USER_FULL_NAME", "Zafer Yılmaz"),
        home_city=os.environ.get("ZORO_HOME_CITY", "İstanbul"),
        confirm_ttl_s=max(30, _int(os.environ.get("ZORO_CONFIRM_TTL"), 180)),
        turn_budget_v1_s=float(os.environ.get("ZORO_TURN_BUDGET_V1", "9") or 9),
        turn_budget_v2_s=float(os.environ.get("ZORO_TURN_BUDGET_V2", "25") or 25),
        max_message_chars=max(200, _int(os.environ.get("ZORO_MAX_MESSAGE_CHARS"), 2000)),
        trust_proxy=_bool(os.environ.get("ZORO_TRUST_PROXY"), True),
    )
