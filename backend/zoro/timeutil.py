"""Time helpers: Turkish formatting and strict parsing of model-supplied times."""

from __future__ import annotations

from datetime import datetime, timedelta
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

DAILY_MS = 86_400_000

TR_WEEKDAYS = ["Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi", "Pazar"]
TR_MONTHS = ["Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran",
             "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık"]


def zone(name: str | None, fallback: str = "Europe/Istanbul") -> ZoneInfo:
    try:
        return ZoneInfo(name or fallback)
    except (ZoneInfoNotFoundError, ValueError):
        return ZoneInfo(fallback)


def now_in(tz: ZoneInfo) -> datetime:
    return datetime.now(tz)


def iso(dt: datetime) -> str:
    return dt.isoformat(timespec="seconds")


def parse_iso(value: object, tz: ZoneInfo) -> datetime | None:
    """Parse an ISO-8601 string. Naive values are taken as local (tz) time."""
    if not isinstance(value, str) or not value.strip():
        return None
    text = value.strip().replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=tz)
    return parsed.astimezone(tz)


def from_millis(ms: object, tz: ZoneInfo) -> datetime | None:
    try:
        return datetime.fromtimestamp(int(ms) / 1000, tz)  # type: ignore[arg-type]
    except (TypeError, ValueError, OverflowError, OSError):
        return None


def to_millis(dt: datetime) -> int:
    return int(dt.timestamp() * 1000)


def long_date(dt: datetime) -> str:
    return f"{dt.day} {TR_MONTHS[dt.month - 1]} {dt.year} {TR_WEEKDAYS[dt.weekday()]}"


def human_time(dt: datetime, now: datetime) -> str:
    """Short spoken form: 'bugün 14:00', 'yarın 09:30', '3 Kasım Pazartesi 14:00'."""
    dt = dt.astimezone(now.tzinfo)
    clock = dt.strftime("%H:%M")
    delta_days = (dt.date() - now.date()).days
    if delta_days == 0:
        return f"bugün {clock}"
    if delta_days == 1:
        return f"yarın {clock}"
    if delta_days == -1:
        return f"dün {clock}"
    label = f"{dt.day} {TR_MONTHS[dt.month - 1]} {TR_WEEKDAYS[dt.weekday()]}"
    if dt.year != now.year:
        label = f"{dt.day} {TR_MONTHS[dt.month - 1]} {dt.year} {TR_WEEKDAYS[dt.weekday()]}"
    return f"{label} {clock}"


def next_occurrence_daily(due: datetime, now: datetime) -> datetime:
    """For a daily task, the next time it fires after now."""
    candidate = due
    if candidate <= now:
        days = (now - candidate).days + 1
        candidate = candidate + timedelta(days=days)
    return candidate
