"""OpenAI list prices, used to turn measured token counts into dollars.

Source: https://developers.openai.com/api/docs/pricing (Standard tier, short
context), checked 2026-10-31. Prices change: override per model with
ZORO_PRICE_OVERRIDES='{"gpt-6-luna": [0.10, 0.01, 0.50]}' (USD per 1M tokens:
input, cached input, output).
"""

from __future__ import annotations

import json
import logging
import os

log = logging.getLogger("zoro.pricing")

PRICES_SOURCE = "https://developers.openai.com/api/docs/pricing"
PRICES_CHECKED = "2026-10-31"

# USD per 1M tokens: (input, cached input, output)
TEXT: dict[str, tuple[float, float, float]] = {
    "gpt-6-luna": (0.10, 0.01, 0.50),
    "gpt-6-sol": (2.00, 0.20, 10.00),
    "gpt-6.1-sol": (2.00, 0.10, 10.00),
    "gpt-6.1-terra": (1.00, 0.05, 5.00),
    "gpt-5-mini": (0.25, 0.025, 2.00),
    "gpt-4o-mini": (0.15, 0.075, 0.60),
}

# Speech models, USD per 1M tokens: (text input, audio output)
SPEECH: dict[str, tuple[float, float]] = {
    "gpt-4o-mini-tts": (0.60, 12.00),
}

# Web search tool: $10.00 per 1k calls; search content tokens are billed at
# the model's input rate and already appear in the response's input tokens.
WEB_SEARCH_PER_CALL = 0.01


def _overrides() -> dict[str, tuple[float, ...]]:
    raw = os.environ.get("ZORO_PRICE_OVERRIDES", "").strip()
    if not raw:
        return {}
    try:
        data = json.loads(raw)
        return {str(k): tuple(float(x) for x in v) for k, v in data.items()}
    except (ValueError, TypeError, AttributeError):
        log.warning("ZORO_PRICE_OVERRIDES is not valid JSON, ignored")
        return {}


def _lookup(table: dict[str, tuple[float, ...]], model: str, size: int) -> tuple[float, ...] | None:
    """Exact name, else the longest known prefix (dated snapshots: gpt-6-luna-2026-...)."""
    merged = {**table, **{k: v for k, v in _overrides().items() if len(v) == size}}
    if model in merged:
        return merged[model]
    for name in sorted(merged, key=len, reverse=True):
        if model.startswith(name + "-"):
            return merged[name]
    return None


def text_cost(model: str, input_tokens: int, cached_tokens: int, output_tokens: int) -> float | None:
    price = _lookup(TEXT, model, 3)  # type: ignore[arg-type]
    if price is None:
        return None
    fresh = max(0, input_tokens - cached_tokens)
    return (fresh * price[0] + cached_tokens * price[1] + output_tokens * price[2]) / 1_000_000


def speech_cost(model: str, input_tokens: int, output_tokens: int) -> float | None:
    price = _lookup(SPEECH, model, 2)  # type: ignore[arg-type]
    if price is None:
        return None
    return (input_tokens * price[0] + output_tokens * price[1]) / 1_000_000


def known_text_model(model: str) -> bool:
    return _lookup(TEXT, model, 3) is not None  # type: ignore[arg-type]
