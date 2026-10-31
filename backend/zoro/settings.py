"""User-changeable feature switches, stored in the meta table as one JSON object.

The phone's settings screen reads and writes these through GET/PUT /settings.
Phone-only switches (call log, background commands, Google Calendar writes)
live on the phone and never reach the server.
"""

from __future__ import annotations

import json
import re
from typing import Any

from .config import Config
from .store import Store

META_KEY = "settings"
VOICES = ["marin", "cedar", "coral", "sage", "ash", "ballad", "verse", "alloy"]
TIME_RE = re.compile(r"^([01]\d|2[0-3]):[0-5]\d$")


def defaults(cfg: Config) -> dict[str, Any]:
    return {
        "web_search": cfg.web_search,
        "natural_voice": False,
        "voice": cfg.tts_voice if cfg.tts_voice in VOICES else "marin",
        "monthly_budget_usd": cfg.monthly_budget_usd,
        "briefing_enabled": True,
        "briefing_time": "10:00",  # the v1 app briefed at 10:00
    }


def load(store: Store, cfg: Config) -> dict[str, Any]:
    result = defaults(cfg)
    raw = store.get_meta(META_KEY)
    if raw:
        try:
            saved = json.loads(raw)
            if isinstance(saved, dict):
                result.update({k: v for k, v in saved.items() if k in result})
        except ValueError:
            pass
    return result


def validate(changes: Any) -> tuple[dict[str, Any], list[str]]:
    """Return the accepted changes and a list of Turkish error messages."""
    if not isinstance(changes, dict):
        return {}, ["Ayarlar bir nesne olmalı."]
    accepted: dict[str, Any] = {}
    errors: list[str] = []
    for key in ("web_search", "natural_voice", "briefing_enabled"):
        if key in changes:
            if isinstance(changes[key], bool):
                accepted[key] = changes[key]
            else:
                errors.append(f"{key} açık/kapalı olmalı.")
    if "voice" in changes:
        if changes["voice"] in VOICES:
            accepted["voice"] = changes["voice"]
        else:
            errors.append("Bilinmeyen ses.")
    if "monthly_budget_usd" in changes:
        value = changes["monthly_budget_usd"]
        if isinstance(value, (int, float)) and not isinstance(value, bool) and 0 < value < 0.01:
            errors.append("Bütçe en az 0,01 dolar olmalı (0 = sınırsız).")
        elif isinstance(value, (int, float)) and not isinstance(value, bool) and 0 <= value <= 1000:
            accepted["monthly_budget_usd"] = round(float(value), 2)
        else:
            errors.append("Aylık bütçe 0 ile 1000 dolar arasında olmalı (0 = sınırsız).")
    if "briefing_time" in changes:
        if isinstance(changes["briefing_time"], str) and TIME_RE.match(changes["briefing_time"]):
            accepted["briefing_time"] = changes["briefing_time"]
        else:
            errors.append("Sabah özeti saati SS:DD olmalı.")
    return accepted, errors


def save(store: Store, cfg: Config, changes: dict[str, Any]) -> dict[str, Any]:
    # Merged under a write lock: two switches flipped at once must not undo each other.
    store.update_json_meta(META_KEY, lambda saved: {**saved, **changes})
    return load(store, cfg)


def paid_allowed(store: Store, settings: dict[str, Any], month: str) -> bool:
    """Paid extras (web search, natural voice) stop once the month's spend reaches the budget."""
    budget = float(settings.get("monthly_budget_usd") or 0)
    return budget <= 0 or store.month_cost(month) < budget
