"""v1 keyword rules, kept ONLY as an offline fallback when the model is down.

The parsing functions are copied from the 23 Oct app.py with one change: they
take `now` instead of reading the clock. Delete and clear-all are deliberately
not available here (v1 deleted without confirmation).
"""

from __future__ import annotations

import re
from datetime import datetime, timedelta

from .tools import Turn, recent_reminder, t_add_task, t_complete_task, t_snooze_task, task_when

CHAR_MAP = str.maketrans({"ç": "c", "ğ": "g", "ı": "i", "ö": "o", "ş": "s", "ü": "u"})

MONTHS = {
    "ocak": 1, "subat": 2, "mart": 3, "nisan": 4, "mayis": 5, "haziran": 6,
    "temmuz": 7, "agustos": 8, "eylul": 9, "ekim": 10, "kasim": 11, "aralik": 12,
}

WEEKDAYS = {"pazartesi": 0, "sali": 1, "carsamba": 2, "persembe": 3, "cuma": 4, "cumartesi": 5, "pazar": 6}


def normalize(value):
    return str(value).strip().lower().replace("’", "'").replace("`", "'").translate(CHAR_MAP)


def is_daily(text):
    return bool(re.search(r"\bher\s+(?:gun|sabah|aksam|gece)\b", normalize(text)))


def is_other_recurring(text):
    text = normalize(text)
    if re.search(r"\bher\s+(?:hafta|ay|yil)\b|\b(?:gunde|haftada)\s+\d+\s+kez\b", text):
        return True
    return any(re.search(r"\bher\s+" + day + r"\b", text) for day in WEEKDAYS)


def clock_time(text):
    text = normalize(text)
    match = re.search(r"\bsaat\s*(\d{1,2})(?:[:.](\d{1,2}))?", text)
    if not match:
        match = re.search(r"\b(\d{1,2})[:.](\d{2})\b", text)
    if not match:
        match = re.search(r"\b(\d{1,2})\s*'?\s*(?:da|de|ta|te)(?:ki)?\b", text)
    if not match:
        return None
    hour = int(match.group(1))
    minute = int(match.group(2) or 0)
    if not (0 <= hour <= 23 and 0 <= minute <= 59):
        return None
    if "aksam" in text or "ogleden sonra" in text:
        if 1 <= hour <= 11:
            hour += 12
    elif "gece" in text:
        if 6 <= hour <= 11:
            hour += 12
    elif "sabah" in text and hour == 12:
        hour = 0
    return hour, minute


def calendar_date(text, now):
    text = normalize(text)
    match = re.search(r"\b(\d{1,2})\s+(" + "|".join(MONTHS) + r")(?:\s+(\d{4}))?\b", text)
    if match:
        day, month = int(match.group(1)), MONTHS[match.group(2)]
        year = int(match.group(3) or now.year)
        explicit_year = bool(match.group(3))
    else:
        match = re.search(r"\b(\d{1,2})[./](\d{1,2})(?:[./](\d{4}))?\b", text)
        if not match:
            return None
        day, month = int(match.group(1)), int(match.group(2))
        year = int(match.group(3) or now.year)
        explicit_year = bool(match.group(3))
    try:
        result = now.replace(year=year, month=month, day=day)
        if not explicit_year and result.date() < now.date():
            result = result.replace(year=year + 1)
        return result
    except ValueError:
        return None


def parse_due_at(text, now: datetime):
    text = normalize(text)
    match = re.search(r"\b(\d+)\s*dakika\s*sonra\b", text)
    if match:
        minutes = int(match.group(1))
        return None if minutes <= 0 else (now + timedelta(minutes=minutes)).isoformat(timespec="seconds")
    match = re.search(r"\b(\d+)\s*saat\s*sonra\b", text)
    if match:
        hours = int(match.group(1))
        return None if hours <= 0 else (now + timedelta(hours=hours)).isoformat(timespec="seconds")
    clock = clock_time(text)
    if clock is None:
        return None
    target, explicit = now, False
    specified = calendar_date(text, now)
    if specified is not None:
        target, explicit = specified, True
    elif "obur gun" in text:
        target, explicit = now + timedelta(days=2), True
    elif "yarin" in text:
        target, explicit = now + timedelta(days=1), True
    elif "bugun" in text:
        explicit = True
    elif not is_daily(text):
        for day, index in WEEKDAYS.items():
            if re.search(r"\b" + day + r"\b", text):
                distance = (index - now.weekday()) % 7 or 7
                target, explicit = now + timedelta(days=distance), True
                break
    due = target.replace(hour=clock[0], minute=clock[1], second=0, microsecond=0)
    if due <= now:
        if explicit:
            return None
        due += timedelta(days=1)
    return due.isoformat(timespec="seconds")


def has_time(text):
    text = normalize(text)
    return bool(
        re.search(r"\b\d+\s*(?:dakika|saat)\s*sonra\b", text)
        or clock_time(text)
        or re.search(r"\b(?:bugun|yarin|obur gun)\b", text)
        or any(re.search(r"\b" + d + r"\b", text) for d in WEEKDAYS)
        or re.search(r"\b\d{1,2}[./]\d{1,2}", text)
        or re.search(r"\b\d{1,2}\s+(?:" + "|".join(MONTHS) + r")\b", text)
    )


def looks_like_reminder(text):
    value = normalize(text)
    if re.match(r"^gorev\s+ekle\b", value):
        return True
    signals = ("hatirlat", "beni uyar", "uyar beni", "bana bildir", "bildirim gonder", "alarm kur",
               "unutmayayim", "unutmayalim", "hatirlatici olustur", "hatirlatici kur")
    if any(s in value for s in signals):
        return True
    if value.endswith("?"):
        return False
    phrases = ("toplantim var", "randevum var", "gorusmem var", "kontrol et", "yapmam gerekiyor")
    return has_time(value) and any(s in value for s in phrases)


def clean_reminder(text):
    text = re.sub(r"^\s*g[öo]rev\s+ekle\s*", "", text.strip(), flags=re.I)
    for pattern in (r"\bbeni\s+uyar\b", r"\buyar\s+beni\b", r"\bbana\s+bildir\b", r"\bbildirim\s+g[öo]nder\b",
                    r"\bdiye\s+hat[ıi]rlat\b", r"\bhat[ıi]rlat(?:[ıi]r\s+m[ıi]s[ıi]n)?\b", r"\balarm\s+kur\b",
                    r"\bunutmayay[ıi]m\b", r"\bunutmayal[ıi]m\b"):
        text = re.sub(pattern, "", text, flags=re.I)
    # v1 ran this twice, the second time on normalize(text), which turned
    # "Su iç" into "su ic" before it was spoken. Once, on the original, is enough.
    text = re.sub(r"\b\d+\s*(?:dakika|saat)\s+sonra\b", "", text, flags=re.I)
    text = re.sub(r"^\s*(?:bana|benim|lütfen|lutfen)\s+", "", text, flags=re.I)
    return re.sub(r"\s+", " ", text).strip(" ,.-?")


def extract_task_number(text):
    text = normalize(text)
    for pattern in (r"\b(\d+)\s*numarali\s+(?:gorevi|hatirlatmayi)\b",
                    r"\b(\d+)\s*[.\-]?\s*(?:gorevi|gorevimi|hatirlatmayi)\b",
                    r"\bgorev\s*(\d+)\b"):
        match = re.search(pattern, text)
        if match:
            return int(match.group(1))
    return None


def is_snooze_intent(value):
    return bool(re.search(r"\b(?:ertele|erteleyebilir|ertelemeyi)\b", normalize(value)))


def snooze_minutes(value):
    match = re.search(r"\b(\d+)\s*(dakika|saat)\b", normalize(value))
    if not match:
        return 10
    minutes = int(match.group(1)) * (60 if match.group(2) == "saat" else 1)
    return minutes if 1 <= minutes <= 1440 else None


def is_pending_list_intent(value):
    text = normalize(value)
    return bool(re.search(r"\b(?:bekleyen|yapilacak|tamamlanmamis)\s+(?:gorev|hatirlatma)", text)
                and re.search(r"\b(?:oku|soyle|say|listele|neler)\b", text))


def is_delete_intent(value):
    text = normalize(value)
    return bool(re.search(r"\b(?:iptal\s+et|sil|kaldir|temizle|vazgec)\b", text)
                and re.search(r"\b(?:gorev\w*|hatirlat\w*|alarm\w*)\b", text))


def _task_by_number(turn: Turn, number: int):
    tasks = turn.store.list_tasks()
    return tasks[number - 1] if 1 <= number <= len(tasks) else None


def _list_reply(turn: Turn, pending_only: bool) -> str:
    tasks = turn.store.list_tasks(pending_only=pending_only)
    if not tasks:
        return "Bekleyen göreviniz bulunmuyor." if pending_only else "Kayıtlı göreviniz bulunmuyor."
    lines = [f"{i}. {t['text']}, {task_when(turn, t)}" for i, t in enumerate(tasks, 1)]
    return ("Bekleyen görevleriniz: " if pending_only else "Kayıtlı görevleriniz: ") + ". ".join(lines)


OFFLINE_NOTE = "Yapay zekâ servisine şu an ulaşamıyorum."


def fallback(turn: Turn, message: str) -> str:
    """Best-effort answer without the model. Always returns something to speak."""
    text = normalize(message)

    if is_delete_intent(message):
        return f"{OFFLINE_NOTE} Silme işlemlerini şu an yapamıyorum, biraz sonra tekrar deneyin."

    if is_snooze_intent(message):
        minutes = snooze_minutes(message)
        if minutes is None:
            return "Erteleme süresi 1 ile 1440 dakika arasında olmalı."
        number = extract_task_number(message)
        task = _task_by_number(turn, number) if number else None
        result = t_snooze_task(turn, task["id"] if task else None, minutes)
        return (f"Tamam, {minutes} dakika erteledim." if result["ok"]
                else "Hangi hatırlatmayı erteleyeceğimi belirleyemedim. Görev numarasını söyleyin.")

    if is_pending_list_intent(message):
        return _list_reply(turn, pending_only=True)

    if re.search(r"\b(?:tamamla|tamamladim|tamamlandi|bitir|bitirdim)\b", text) and re.search(
            r"\b(?:gorev|hatirlatma|tamamladim|tamamla|bitirdim)\b", text):
        number = extract_task_number(message)
        task = _task_by_number(turn, number) if number else None
        if task is None and number is None:
            recent = recent_reminder(turn)
            task = recent[0] if len(recent) == 1 else None
        if task is None:
            return "Hangi görevi tamamladığınızı belirleyemedim. Görev numarasını söyleyin."
        t_complete_task(turn, task["id"])
        return f"Görevi tamamladım: {task['text']}."

    if any(p in text for p in ("gorevlerimi soyle", "gorevlerim neler", "gorevlerimi goster", "gorevleri listele",
                               "gorevlerimi listele", "gorev listemi soyle", "gorev listemi goster",
                               "gorevleri say", "gorevlerimi oku")):
        return _list_reply(turn, pending_only=False)

    if looks_like_reminder(message):
        if is_other_recurring(message) and not is_daily(message):
            return "Haftalık ve aylık tekrar henüz hazır değil. Günlük hatırlatma oluşturabilirim."
        cleaned = clean_reminder(message) or "Belirlediğiniz görevin zamanı geldi"
        due_at = parse_due_at(message, turn.now)
        repeat = "daily" if is_daily(message) else "none"
        if due_at is None:
            return "Geçerli bir zaman söyleyin. Örneğin: iki dakika sonra su içmemi hatırlat."
        result = t_add_task(turn, cleaned, due_at, repeat)
        if not result["ok"]:
            return result["error"]
        extra = " Her gün aynı saatte tekrarlanacak." if repeat == "daily" else ""
        return f"Tamam {turn.cfg.user_title}. {cleaned} hatırlatmasını kurdum, {result['zaman']}.{extra}"

    return f"{OFFLINE_NOTE} Hatırlatma kurmak ya da görevlerinizi okumak gibi temel işleri yapabilirim, diğerleri için biraz sonra tekrar deneyin."

