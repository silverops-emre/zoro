"""Metering of every OpenAI call and the monthly cost summary behind GET /usage.

Nothing here is guessed when data exists: month-to-date figures are summed
from the recorded token counts. Estimates (for switched-off features, or
before any data) say so in their "basis" field.
"""

from __future__ import annotations

import calendar
import json
import logging
from datetime import datetime, timedelta
from typing import Any, Callable

from . import pricing
from .store import Store

log = logging.getLogger("zoro.costs")

FEATURES = [
    ("beyin", "Yapay zekâ (her komut)"),
    ("web_arama", "Güncel bilgi (web araması)"),
    ("dogal_ses", "Doğal ses"),
    ("sabah_ozeti", "Sabah özeti"),
]
ASSUMED_ASKS_PER_DAY = 20
ASSUMED_SEARCHES_PER_DAY = 3
# Measured on 2026-10-31: an 8.0 s Turkish reply = 51 text input + 204 audio output tokens.
TTS_REPLY_TOKENS = (51, 204)
# Search content tokens per call, billed at the model's input rate (OpenAI bills
# gpt-4o-mini searches as a fixed 8,000-token block; used here as the estimate).
SEARCH_CONTENT_TOKENS = 8000


def _get(obj: Any, name: str, default: Any = None) -> Any:
    if obj is None:
        return default
    if isinstance(obj, dict):
        return obj.get(name, default)
    return getattr(obj, name, default)


def record_response(store: Store, now: datetime, feature: str, model: str, response: Any,
                    turn_id: str | None = None) -> None:
    """Record one Responses API call (tokens + web searches)."""
    try:
        usage = _get(response, "usage")
        input_tokens = int(_get(usage, "input_tokens", 0) or 0)
        cached = int(_get(_get(usage, "input_tokens_details"), "cached_tokens", 0) or 0)
        output_tokens = int(_get(usage, "output_tokens", 0) or 0)
        searches = sum(1 for item in (_get(response, "output") or []) if _get(item, "type") == "web_search_call")
        if usage is None and not searches:
            return
        cost = pricing.text_cost(model, input_tokens, cached, output_tokens)
        if searches:
            # The round that searched is billed to web search, content tokens included.
            feature = "web_arama"
            cost = (cost or 0.0) + searches * pricing.WEB_SEARCH_PER_CALL
        store.add_usage(created_at=now, feature=feature, model=model, input_tokens=input_tokens,
                        cached_tokens=cached, output_tokens=output_tokens, searches=searches,
                        cost_usd=cost, turn_id=turn_id)
    except Exception:  # metering must never break a reply
        log.exception("usage record failed")


def record_speech(store: Store, now: datetime, model: str, usage: dict[str, Any] | None) -> None:
    try:
        input_tokens = int((usage or {}).get("input_tokens") or 0)
        output_tokens = int((usage or {}).get("output_tokens") or 0)
        store.add_usage(created_at=now, feature="dogal_ses", model=model, input_tokens=input_tokens,
                        output_tokens=output_tokens, cost_usd=pricing.speech_cost(model, input_tokens, output_tokens))
    except Exception:
        log.exception("speech usage record failed")


def usd_try_rate(store: Store, now: datetime, fetch: Callable[[], float | None]) -> float | None:
    """TCMB USD selling rate, fetched at most once a day."""
    today = now.strftime("%Y-%m-%d")
    cached = store.get_meta("usd_try")
    if cached:
        try:
            data = json.loads(cached)
            if data.get("date") == today:
                return data.get("rate")
        except ValueError:
            pass
    try:
        rate = fetch()
    except Exception:
        log.warning("USD/TRY rate unavailable")
        rate = None
    if rate:
        store.set_meta("usd_try", json.dumps({"date": today, "rate": rate}))
        return rate
    if cached:
        try:
            return json.loads(cached).get("rate")  # yesterday's rate beats nothing
        except ValueError:
            return None
    return None


def asks_per_day(store: Store, now: datetime) -> float | None:
    """Average commands per day over the last 14 days of metered use."""
    rows = [r for r in store.usage_since(now - timedelta(days=14)) if r["feature"] in ("beyin", "web_arama")]
    turns = {r["turn_id"] or r["created_at"] for r in rows}
    if not rows:
        return None
    first = datetime.fromisoformat(rows[0]["created_at"])
    days = max(1.0, (now - first).total_seconds() / 86400)
    return round(len(turns) / days, 1)


def summary(store: Store, settings: dict[str, Any], model: str, tts_model: str, now: datetime,
            rate_fetch: Callable[[], float | None]) -> dict[str, Any]:
    month = now.strftime("%Y-%m")
    days_in_month = calendar.monthrange(now.year, now.month)[1]
    rows = store.usage_rows(month)

    elapsed = 0.0
    if rows:
        first = datetime.fromisoformat(rows[0]["created_at"])
        elapsed = (now - first).total_seconds() / 86400
    span = max(1.0, elapsed)
    remaining_days = max(0.0, days_in_month - (now.day - 1) - now.hour / 24)

    measured_asks = asks_per_day(store, now)
    daily_asks = measured_asks if measured_asks is not None else ASSUMED_ASKS_PER_DAY

    text_price = pricing.text_cost(model, 1_000_000, 0, 0)
    per_search = (pricing.WEB_SEARCH_PER_CALL + SEARCH_CONTENT_TOKENS * text_price / 1_000_000
                  if text_price is not None else pricing.WEB_SEARCH_PER_CALL)
    per_tts = pricing.speech_cost(tts_model, *TTS_REPLY_TOKENS)

    enabled = {
        "beyin": True,
        "web_arama": bool(settings.get("web_search")),
        "dogal_ses": bool(settings.get("natural_voice")),
        "sabah_ozeti": bool(settings.get("briefing_enabled")),
    }

    features = []
    total = 0.0
    total_projected = 0.0
    unknown_price = False
    for key, label in FEATURES:
        mine = [r for r in rows if r["feature"] == key]
        cost = sum(r["cost_usd"] or 0.0 for r in mine)
        unknown_price |= any(r["cost_usd"] is None for r in mine)
        if key == "web_arama":
            uses = sum(r["searches"] for r in mine)
        elif key in ("beyin", "sabah_ozeti"):
            uses = len({r["turn_id"] or r["created_at"] for r in mine})
        else:
            uses = len(mine)
        per_use = round(cost / uses, 5) if uses else None
        if per_use is None:
            # No measured ask yet: the brain gets no per-ask figure at all.
            per_use = {"web_arama": per_search, "dogal_ses": per_tts}.get(key)

        daily_uses = {"beyin": daily_asks, "web_arama": ASSUMED_SEARCHES_PER_DAY,
                      "dogal_ses": daily_asks, "sabah_ozeti": 1}[key]
        if uses and enabled[key]:
            projected = cost + cost / span * remaining_days
            basis = "olculen"
        elif per_use is not None:
            projected = cost + per_use * daily_uses * remaining_days
            basis = "tahmin"
        else:
            projected = None
            basis = "veri_yok"
        if_on_monthly = round(per_use * daily_uses * 30, 2) if per_use is not None else None

        total += cost
        if enabled[key] and projected is not None:
            total_projected += projected
        features.append({
            "key": key,
            "label": label,
            "enabled": enabled[key],
            "cost_usd": round(cost, 4),
            "uses": uses,
            "per_use_usd": per_use,
            "projected_usd": round(projected, 2) if projected is not None and enabled[key] else None,
            "monthly_if_on_usd": if_on_monthly,
            "assumed_daily_uses": daily_uses,
            "basis": basis,
        })

    budget = float(settings.get("monthly_budget_usd") or 0)
    rate = usd_try_rate(store, now, rate_fetch)
    return {
        "month": month,
        "days_in_month": days_in_month,
        "days_measured": round(elapsed, 1),
        "little_data": elapsed < 2,
        "total_usd": round(total, 4),
        "projected_usd": round(total_projected, 2),
        "budget_usd": budget,
        "budget_reached": budget > 0 and total >= budget,
        "usd_try": rate,
        "asks_per_day": measured_asks,
        "asks_per_day_assumed": measured_asks is None,
        "unknown_price": unknown_price or text_price is None,
        "model": model,
        "tts_model": tts_model,
        "tts_shutdown": "2027-01-06" if tts_model.startswith("gpt-4o-mini-tts") else None,
        "prices_checked": pricing.PRICES_CHECKED,
        "prices_source": pricing.PRICES_SOURCE,
        "features": features,
    }
