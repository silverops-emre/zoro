
from flask import Flask, request, jsonify
from dotenv import load_dotenv
from openai import OpenAI
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo
from threading import RLock
from pathlib import Path
import json
import os
import re
import uuid

# ==========================================
# ZORO - KISISEL YAPAY ZEKA ASISTANI
# ==========================================

BASE_DIR = Path(__file__).resolve().parent
load_dotenv(BASE_DIR / ".env")

app = Flask(__name__)
client = OpenAI()

ISTANBUL = ZoneInfo("Europe/Istanbul")
MEMORY_FILE = BASE_DIR / "zoro_memory.json"
TASKS_FILE = BASE_DIR / "zoro_tasks.json"
MODEL_NAME = "gpt-6-luna"
DAILY_MS = 86400000

task_lock = RLock()

CHAR_MAP = str.maketrans({
    "ç": "c", "ğ": "g", "ı": "i",
    "ö": "o", "ş": "s", "ü": "u"
})

MONTHS = {
    "ocak": 1, "subat": 2, "mart": 3,
    "nisan": 4, "mayis": 5, "haziran": 6,
    "temmuz": 7, "agustos": 8,
    "eylul": 9, "ekim": 10,
    "kasim": 11, "aralik": 12
}

WEEKDAYS = {
    "pazartesi": 0, "sali": 1,
    "carsamba": 2, "persembe": 3,
    "cuma": 4, "cumartesi": 5,
    "pazar": 6
}


# ==========================================
# YARDIMCI FONKSIYONLAR
# ==========================================

def normalize(value):
    return (
        str(value).strip().lower()
        .replace("’", "'")
        .replace("`", "'")
        .translate(CHAR_MAP)
    )


def now_istanbul():
    return datetime.now(ISTANBUL)


def load_json_file(filename):
    if not os.path.exists(filename):
        return []
    try:
        with open(filename, "r", encoding="utf-8") as f:
            value = json.load(f)
        return value if isinstance(value, list) else []
    except Exception as error:
        print("Dosya okuma hatası:", error)
        return []


def save_json_file(filename, value):
    temporary = str(filename) + ".tmp"
    with open(temporary, "w", encoding="utf-8") as f:
        json.dump(
            value, f,
            ensure_ascii=False,
            indent=2
        )
    os.replace(temporary, filename)


history = load_json_file(MEMORY_FILE)
tasks = load_json_file(TASKS_FILE)

# ZORO_PERSONAL_MEMORY_V1
PERSONAL_MEMORY_FILE = BASE_DIR / "zoro_personal_memory.json"
personal_memory_lock = RLock()

personal_memory = load_json_file(PERSONAL_MEMORY_FILE)


def save_personal_memory():
    save_json_file(PERSONAL_MEMORY_FILE, personal_memory)


def remember_personal_fact(key, value):
    key = str(key).strip()
    value = str(value).strip()

    if not key or not value:
        return False

    with personal_memory_lock:
        for item in personal_memory:
            if normalize(item.get("key", "")) == normalize(key):
                item["key"] = key
                item["value"] = value
                save_personal_memory()
                return True

        personal_memory.append({
            "key": key,
            "value": value
        })

        save_personal_memory()
        return True


def recall_personal_fact(key):
    key = normalize(key)

    with personal_memory_lock:
        for item in personal_memory:
            if normalize(item.get("key", "")) == key:
                return item.get("value")

    return None


def forget_personal_fact(key):
    key = normalize(key)

    with personal_memory_lock:
        for index, item in enumerate(personal_memory):
            if normalize(item.get("key", "")) == key:
                personal_memory.pop(index)
                save_personal_memory()
                return True

    return False


def list_personal_facts():
    with personal_memory_lock:
        return [dict(item) for item in personal_memory]


# ZORO_PERSONAL_MEMORY_V1_END



def save_tasks():
    save_json_file(TASKS_FILE, tasks)


def get_text(task):
    if isinstance(task, dict):
        return str(task.get("text", ""))
    return str(task)


def get_due(task):
    if isinstance(task, dict):
        return task.get("due_at")
    return None


def get_id(task):
    if isinstance(task, dict):
        return str(
            task.get("id")
            or task.get("alarm_id")
            or ""
        )
    return ""


def is_done(task):
    return bool(
        task.get("done", False)
    ) if isinstance(task, dict) else False


def parse_stored_date(value):
    if not value:
        return None
    try:
        result = datetime.fromisoformat(str(value))
        if result.tzinfo is None:
            result = result.replace(tzinfo=ISTANBUL)
        return result
    except (ValueError, TypeError):
        return None


# ==========================================
# TARIH VE SAAT COZUMLEME
# ==========================================

def is_daily(text):
    return bool(re.search(
        r"\bher\s+(?:gun|sabah|aksam|gece)\b",
        normalize(text)
    ))


def is_other_recurring(text):
    text = normalize(text)
    if re.search(
        r"\bher\s+(?:hafta|ay|yil)\b"
        r"|\b(?:gunde|haftada)\s+\d+\s+kez\b",
        text
    ):
        return True
    return any(
        re.search(r"\bher\s+" + day + r"\b", text)
        for day in WEEKDAYS
    )


def clock_time(text):
    text = normalize(text)

    match = re.search(
        r"\bsaat\s*(\d{1,2})(?:[:.](\d{1,2}))?",
        text
    )
    if not match:
        match = re.search(
            r"\b(\d{1,2})[:.](\d{2})\b",
            text
        )
    if not match:
        match = re.search(
            r"\b(\d{1,2})\s*'?\s*(?:da|de|ta|te)(?:ki)?\b",
            text
        )
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
    match = re.search(
        r"\b(\d{1,2})\s+("
        + "|".join(MONTHS)
        + r")(?:\s+(\d{4}))?\b",
        text
    )

    if match:
        day = int(match.group(1))
        month = MONTHS[match.group(2)]
        year = int(match.group(3) or now.year)
        explicit_year = bool(match.group(3))
    else:
        match = re.search(
            r"\b(\d{1,2})[./](\d{1,2})"
            r"(?:[./](\d{4}))?\b",
            text
        )
        if not match:
            return None
        day = int(match.group(1))
        month = int(match.group(2))
        year = int(match.group(3) or now.year)
        explicit_year = bool(match.group(3))

    try:
        result = now.replace(
            year=year, month=month, day=day
        )
        if not explicit_year and result.date() < now.date():
            result = result.replace(year=year + 1)
        return result
    except ValueError:
        return None


def parse_due_at(text):
    now = now_istanbul()
    text = normalize(text)

    match = re.search(
        r"\b(\d+)\s*dakika\s*sonra\b", text
    )
    if match:
        minutes = int(match.group(1))
        if minutes <= 0:
            return None
        return (
            now + timedelta(minutes=minutes)
        ).isoformat(timespec="seconds")

    match = re.search(
        r"\b(\d+)\s*saat\s*sonra\b", text
    )
    if match:
        hours = int(match.group(1))
        if hours <= 0:
            return None
        return (
            now + timedelta(hours=hours)
        ).isoformat(timespec="seconds")

    clock = clock_time(text)
    if clock is None:
        return None

    target = now
    explicit = False

    specified = calendar_date(text, now)

    if specified is not None:
        target = specified
        explicit = True
    elif "obur gun" in text:
        target = now + timedelta(days=2)
        explicit = True
    elif "yarin" in text:
        target = now + timedelta(days=1)
        explicit = True
    elif "bugun" in text:
        explicit = True
    elif not is_daily(text):
        for day, index in WEEKDAYS.items():
            if re.search(r"\b" + day + r"\b", text):
                distance = (index - now.weekday()) % 7 or 7
                target = now + timedelta(days=distance)
                explicit = True
                break

    due = target.replace(
        hour=clock[0],
        minute=clock[1],
        second=0,
        microsecond=0
    )

    if due <= now:
        if explicit:
            return None
        due += timedelta(days=1)

    return due.isoformat(timespec="seconds")


def has_time(text):
    text = normalize(text)
    return bool(
        re.search(
            r"\b\d+\s*(?:dakika|saat)\s*sonra\b", text
        )
        or clock_time(text)
        or re.search(
            r"\b(?:bugun|yarin|obur gun)\b", text
        )
        or any(
            re.search(r"\b" + d + r"\b", text)
            for d in WEEKDAYS
        )
        or re.search(r"\b\d{1,2}[./]\d{1,2}", text)
        or re.search(
            r"\b\d{1,2}\s+(?:"
            + "|".join(MONTHS) + r")\b",
            text
        )
    )


# ==========================================
# HATIRLATICI KOMUT TANIMA
# ==========================================

def looks_like_reminder(text):
    value = normalize(text)

    if re.match(r"^gorev\s+ekle\b", value):
        return True

    signals = (
        "hatirlat", "beni uyar", "uyar beni",
        "bana bildir", "bildirim gonder",
        "alarm kur", "unutmayayim",
        "unutmayalim", "hatirlatici olustur",
        "hatirlatici kur"
    )

    if any(s in value for s in signals):
        return True

    if value.endswith("?"):
        return False

    phrases = (
        "toplantim var", "randevum var",
        "gorusmem var", "kontrol et",
        "yapmam gerekiyor"
    )
    return has_time(value) and any(
        s in value for s in phrases
    )


def clean_reminder(text):
    text = re.sub(
        r"^\s*g[öo]rev\s+ekle\s*",
        "", text.strip(), flags=re.I
    )

    patterns = (
        r"\bbeni\s+uyar\b",
        r"\buyar\s+beni\b",
        r"\bbana\s+bildir\b",
        r"\bbildirim\s+g[öo]nder\b",
        r"\bdiye\s+hat[ıi]rlat\b",
        r"\bhat[ıi]rlat(?:[ıi]r\s+m[ıi]s[ıi]n)?\b",
        r"\balarm\s+kur\b",
        r"\bunutmayay[ıi]m\b",
        r"\bunutmayal[ıi]m\b"
    )

    for pattern in patterns:
        text = re.sub(pattern, "", text, flags=re.I)

    # Zaman ifadesi görev metninden çıkarılır.
    text = re.sub(
        r"\b\d+\s*(?:dakika|saat)\s+sonra\b",
        "", text, flags=re.I
    )
    text = re.sub(
        r"\b\d+\s*(?:dakika|saat)\s+sonra\b",
        "", normalize(text), flags=re.I
    )
    text = re.sub(
        r"^\s*(?:bana|benim|lütfen|lutfen)\s+",
        "", text, flags=re.I
    )

    return re.sub(r"\s+", " ", text).strip(" ,.-?")


# ==========================================
# GOREV KAYIT SISTEMI
# ==========================================

def add_task(text, due_at, repeat_interval=0):
    task = {
        "id": uuid.uuid4().hex,
        "text": text,
        "done": False,
        "due_at": due_at,
        "notified": False,
        "repeat_interval": repeat_interval
    }
    with task_lock:
        tasks.append(task)
        save_tasks()
    return task


def task_response(task, reply):
    return jsonify({
        "reply": reply,
        "action": "add_task",
        "task_text": get_text(task),
        "due_at": get_due(task),
        "alarm_id": get_id(task),
        "repeat_interval": task.get(
            "repeat_interval", 0
        )
    })


def extract_task_number(text):
    text = normalize(text)

    patterns = (
        r"\b(\d+)\s*numarali\s+"
        r"(?:gorevi|hatirlatmayi)\b",
        r"\b(\d+)\s*[.\-]?\s*"
        r"(?:gorevi|gorevimi|hatirlatmayi)\b",
        r"\bgorev\s*(\d+)\b"
    )

    for pattern in patterns:
        match = re.search(pattern, text)
        if match:
            return int(match.group(1))

    return None


def is_delete_intent(value):
    text = normalize(value)

    if re.search(
        r"\b(?:silmemi|iptal etmemi|kaldirmami)"
        r"\s+hatirlat\b",
        text
    ):
        return False

    action = re.search(
        r"\b(?:iptal\s+et|sil|kaldir|temizle|vazgec)\b",
        text
    )
    subject = re.search(
        r"\b(?:gorev\w*|hatirlat\w*|alarm\w*)\b",
        text
    )
    return bool(action and subject)


def find_delete_targets(text):
    number = extract_task_number(text)

    if number is not None:
        return [number] if 1 <= number <= len(tasks) else []

    clock = clock_time(text)
    if clock is not None:
        matched = []
        for i, task in enumerate(tasks, 1):
            due = parse_stored_date(get_due(task))
            if due and not is_done(task):
                if (due.hour, due.minute) == clock:
                    matched.append(i)
        return matched

    text = normalize(text)
    text = re.sub(
        r"\b(?:iptal|et|sil|kaldir|temizle|vazgec|"
        r"gorev\w*|hatirlat\w*|alarm\w*|"
        r"lutfen|olan|icin|saat|gunluk|"
        r"her|gun|tekrar\w*)\b",
        " ", text
    )

    words = [
        word for word in re.findall(r"[a-z]+", text)
        if len(word) >= 4
    ]

    if not words:
        return []

    return [
        i for i, task in enumerate(tasks, 1)
        if not is_done(task)
        and all(
            word[:4] in normalize(get_text(task))
            for word in words
        )
    ]


def cancel_response(number):
    with task_lock:
        task = tasks.pop(number - 1)
        save_tasks()

    alarm_id = get_id(task)
    due_at = get_due(task)

    return jsonify({
        "reply": "Görevi sildim: " + get_text(task),
        "action": "delete_task",
        "task_number": number,
        "task_text": get_text(task),
        "alarm_id": alarm_id,
        "due_at": due_at,
        "cancel_alarm_ids": [alarm_id] if alarm_id else [],
        "cancel_due_ats": [due_at] if due_at else []
    })


def complete_response(number):
    with task_lock:
        task = tasks[number - 1]
        if not isinstance(task, dict):
            return jsonify({
                "reply": "Bu görev biçimi desteklenmiyor."
            })

        if is_done(task):
            return jsonify({
                "reply": "Bu görev zaten tamamlanmış."
            })

        task["done"] = True
        task["notified"] = True
        save_tasks()

    return jsonify({
        "reply": "Görevi tamamladım: " + get_text(task),
        "action": "complete_task",
        "task_number": number,
        "task_text": get_text(task),
        "alarm_id": get_id(task),
        "due_at": get_due(task)
    })


# ==========================================
# SON HATIRLATMAYI BUL
# ==========================================

def recent_reminder_candidates():
    now = now_istanbul()
    candidates = []

    for index, task in enumerate(tasks, 1):
        if is_done(task):
            continue

        due = parse_stored_date(get_due(task))
        if due is None:
            continue

        if (
            isinstance(task, dict)
            and task.get("repeat_interval") == DAILY_MS
        ):
            due = due.replace(
                year=now.year,
                month=now.month,
                day=now.day
            )
            if due > now:
                due -= timedelta(days=1)

        age = (now - due).total_seconds()

        # Son 30 dakika içinde zamanı gelen görev.
        if 0 <= age <= 1800:
            candidates.append((age, index))

    candidates.sort(key=lambda item: item[0])

    if not candidates:
        return []

    # Yakın zamanlı iki alarm varsa tahmin etme.
    nearest = candidates[0][0]

    return [
        index for age, index in candidates
        if age - nearest < 120
    ]


def is_snooze_intent(value):
    text = normalize(value)
    return bool(re.search(
        r"\b(?:ertele|erteleyebilir|ertelemeyi)\b",
        text
    ))


def snooze_minutes(value):
    text = normalize(value)
    match = re.search(
        r"\b(\d+)\s*(dakika|saat)\b",
        text
    )
    if not match:
        return 10

    amount = int(match.group(1))
    minutes = amount * (
        60 if match.group(2) == "saat" else 1
    )

    return minutes if 1 <= minutes <= 1440 else None


def snooze_task(message):
    minutes = snooze_minutes(message)

    if minutes is None:
        return jsonify({
            "reply": "Erteleme süresi 1 ile 1440 dakika arasında olmalı."
        })

    number = extract_task_number(message)

    if number is not None:
        if not 1 <= number <= len(tasks):
            return jsonify({
                "reply": "Bu numarada görev bulamadım."
            })
        matches = [number]
    else:
        matches = recent_reminder_candidates()

    if len(matches) != 1:
        return jsonify({
            "reply": (
                "Hangi hatırlatmayı erteleyeceğimi "
                "belirleyemedim. Lütfen görev numarasını "
                "söyleyin veya hatırlatma çaldıktan "
                "hemen sonra tekrar deneyin."
            )
        })

    original = tasks[matches[0] - 1]

    if is_done(original):
        return jsonify({
            "reply": "Tamamlanmış görevi erteleyemem."
        })

    text = get_text(original)
    due = (
        now_istanbul() + timedelta(minutes=minutes)
    ).isoformat(timespec="seconds")

    # Günlük ana görev korunur.
    # Erteleme için ayrı, tek seferlik görev açılır.
    task = add_task(
        text=text,
        due_at=due,
        repeat_interval=0
    )

    return task_response(
        task,
        f"Tamam Zafer Bey. {text} hatırlatmasını "
        f"{minutes} dakika erteledim. "
        "Günlük hatırlatmanız varsa korunuyor."
    )


# ==========================================
# GOREVLERI SESLI LISTELE
# ==========================================

def is_pending_list_intent(value):
    text = normalize(value)
    return bool(
        re.search(
            r"\b(?:bekleyen|yapilacak|tamamlanmamis)\s+"
            r"(?:gorev|hatirlatma)",
            text
        )
        and re.search(
            r"\b(?:oku|soyle|say|listele|neler)\b",
            text
        )
    )


def list_tasks_reply(pending_only=False):
    selected = [
        (i, task)
        for i, task in enumerate(tasks, 1)
        if not pending_only or not is_done(task)
    ]

    if not selected:
        return jsonify({
            "reply": (
                "Bekleyen göreviniz bulunmuyor."
                if pending_only else
                "Kayıtlı göreviniz bulunmuyor."
            )
        })

    lines = []

    for index, task in selected:
        due = parse_stored_date(get_due(task))
        when = (
            ", saat " + due.strftime("%H:%M")
            if due else ""
        )

        repeat = ""
        if (
            isinstance(task, dict)
            and task.get("repeat_interval") == DAILY_MS
        ):
            repeat = ", her gün tekrarlanır"

        status = (
            "tamamlandı" if is_done(task)
            else "bekliyor"
        )

        lines.append(
            f"{index}. {get_text(task)}"
            f"{when}, {status}{repeat}"
        )

    prefix = (
        "Bekleyen görevleriniz: "
        if pending_only else
        "Kayıtlı görevleriniz: "
    )

    return jsonify({
        "reply": prefix + ". ".join(lines)
    })


# ==========================================
# ANA ZORO API
# ==========================================

def number_to_turkish(value):
    words = {
        0: "sıfır", 1: "bir", 2: "iki",
        3: "üç", 4: "dört", 5: "beş",
        6: "altı", 7: "yedi", 8: "sekiz",
        9: "dokuz", 10: "on", 20: "yirmi",
        30: "otuz", 40: "kırk", 50: "elli",
        60: "altmış", 70: "yetmiş",
        80: "seksen", 90: "doksan"
    }

    text = str(value).strip()

    if not text.isdigit():
        return text

    number = int(text)

    if number in words:
        return words[number]

    if 10 < number < 100:
        tens = (number // 10) * 10
        ones = number % 10
        return words[tens] + " " + words[ones]

    return text


@app.route("/ask", methods=["POST"])
def ask():
    data = request.get_json(silent=True) or {}
    message = str(data.get("message", "")).strip()

    if not message:
        return jsonify({
            "reply": "Mesaj boş olamaz."
        }), 400

    text = normalize(message)

    # 1 - ERTELEME
    if is_snooze_intent(message):
        return snooze_task(message)

    # 2 - BEKLEYEN GOREVLER
    if is_pending_list_intent(message):
        return list_tasks_reply(pending_only=True)

    # 3 - GOREV SILME
    if is_delete_intent(message):
        clear_all = bool(re.search(
            r"\b(?:tum|butun|hepsi|hepsini)\b",
            text
        )) or "gorevlerimi temizle" in text

        if clear_all:
            ids = [
                get_id(task) for task in tasks
                if get_id(task)
            ]
            dues = [
                get_due(task) for task in tasks
                if get_due(task)
            ]
            with task_lock:
                tasks.clear()
                save_tasks()

            return jsonify({
                "reply": "Tüm görevlerinizi sildim.",
                "action": "clear_tasks",
                "cancel_alarm_ids": ids,
                "cancel_due_ats": dues
            })

        matches = find_delete_targets(message)

        if not matches:
            return jsonify({
                "reply": (
                    "Hangi görevi sileyim? "
                    "Görev numarasını söyleyin."
                )
            })

        if len(matches) > 1:
            return jsonify({
                "reply": (
                    "Birden fazla uygun görev var. "
                    "Lütfen görev numarasını söyleyin."
                )
            })

        return cancel_response(matches[0])

    # 4 - GOREV TAMAMLAMA
    if re.search(
        r"\b(?:tamamla|tamamladim|tamamlandi|"
        r"bitir|bitirdim)\b",
        text
    ) and re.search(
        r"\b(?:gorev|hatirlatma|tamamladim|"
        r"tamamla|bitirdim)\b",
        text
    ):
        number = extract_task_number(message)

        if number is None:
            matches = recent_reminder_candidates()
            if len(matches) != 1:
                return jsonify({
                    "reply": (
                        "Hangi görevi tamamladığınızı "
                        "belirleyemedim. "
                        "Lütfen görev numarasını söyleyin."
                    )
                })
            number = matches[0]

        if not 1 <= number <= len(tasks):
            return jsonify({
                "reply": "Görev numarası bulunamadı."
            })

        return complete_response(number)

    # 5 - GOREV LISTELEME
    list_phrases = (
        "gorevlerimi soyle",
        "gorevlerim neler",
        "gorevlerimi goster",
        "gorevleri listele",
        "gorevlerimi listele",
        "gorev listemi soyle",
        "gorev listemi goster",
        "gorevleri say",
        "gorevlerimi oku"
    )

    if any(p in text for p in list_phrases):
        return list_tasks_reply()

    # 6 - HATIRLATICI OLUSTURMA
    if looks_like_reminder(message):

        if (
            is_other_recurring(message)
            and not is_daily(message)
        ):
            return jsonify({
                "reply": (
                    "Haftalık ve aylık tekrar henüz hazır değil. "
                    "Günlük hatırlatma oluşturabilirim."
                )
            })

        if (
            is_daily(message)
            and is_other_recurring(message)
        ):
            return jsonify({
                "reply": (
                    "Aynı hatırlatma için birden fazla "
                    "tekrar kuralı belirtemezsiniz."
                )
            })

        cleaned = clean_reminder(message)
        due_at = parse_due_at(message)

        repeat = DAILY_MS if is_daily(message) else 0

        if not cleaned:
            cleaned = "Belirlediğiniz görevin zamanı geldi"

        if due_at is None:
            if text.startswith("gorev ekle") and not repeat:
                task = add_task(cleaned, None)
                return task_response(
                    task,
                    "Görevi kaydettim ancak saat "
                    "belirtilmediği için alarm kurmadım."
                )

            return jsonify({
                "reply": (
                    "Geçerli bir zaman söyleyin. "
                    "Örneğin: İki dakika sonra "
                    "su içmemi hatırlat."
                )
            })

        task = add_task(
            cleaned,
            due_at,
            repeat
        )

        due = parse_stored_date(due_at)
        date_label = due.strftime(
            "%d.%m.%Y %H:%M"
        )

        extra = (
            " Her gün aynı saatte tekrarlanacak."
            if repeat else ""
        )

        return task_response(
            task,
            f"Tamam Zafer Bey. "
            f"{cleaned} hatırlatmasını oluşturdum. "
            f"Zaman: {date_label}.{extra}"
        )

    # ZORO_MEMORY_VOICE_SAVE_V2
    # "Bunu hatirla" ve "bunu unutma" bilgiyi kaydeder.
    # "Bunu unut" bu bolumde kayit veya silme yapmaz.
    memory_text = normalize(message)

    save_match = re.search(
        r"(?:,\s*|\s+)bunu\s+(?:hatirla|unutma)[\s.!?]*$",
        memory_text
    )

    if save_match:
        fact = memory_text[:save_match.start()].strip()

        fact = re.sub(
            r"^(?:hey\s+)?zoro[\s,:]+",
            "",
            fact
        ).strip()

        supported_keys = (
            "en sevdigim renk",
            "en sevdigim takim",
            "hayatin anlami",
            "ugurlu sayim",
            "arabamin rengi"
        )

        saved = False

        for key in supported_keys:
            if fact.startswith(key + " "):
                value = fact[len(key):].strip()

                if value:
                    saved = remember_personal_fact(
                        key, value
                    )

                    if saved:
                        return jsonify({
                            "reply": (
                                f"{key}: {value} bilgisini "
                                "kalıcı hafızama kaydettim."
                            )
                        })

                break

        return jsonify({
            "reply": (
                "Bilgiyi güvenilir şekilde ayıramadım. "
                "Örneğin: Zoro, hayatın anlamı "
                "Galatasaray, bunu unutma."
            )
        })

    # ZORO_MEMORY_RECALL_V1
    # Kalici hafizadan kayitli bilgiyi sorgula.
    recall_text = normalize(message)

    if re.search(
        r"\b(?:ugurlu\s+sayim\s+kac|"
        r"ugurlu\s+sayimi\s+hatirliyor\s+musun)\b",
        recall_text
    ):
        value = recall_personal_fact("ugurlu sayim")

        if value is not None:
            return jsonify({
                "reply": f"Uğurlu sayınız {number_to_turkish(value)}."
            })

        return jsonify({
            "reply": "Uğurlu sayınızı henüz hafızama kaydetmedim."
        })

    # 7 - NORMAL YAPAY ZEKA SOHBETI
    history.append({
        "role": "user",
        "content": message
    })

    try:
        response = client.responses.create(
            model=MODEL_NAME,
            instructions=(
                "Sen Zoro'sun. "
                "Zafer Yılmaz'ın Türkçe kişisel "
                "yapay zeka asistanısın. "
                "Doğal, açık ve kısa konuş. "
                "Gerçekte yapmadığın bir işlemi "
                "yapılmış gibi söyleme. "
                "Hatırlatıcılar uygulamanın "
                "görev sistemi tarafından yönetilir. "
                "Kullanıcıya gerektiğinde görev "
                "komutlarının nasıl verileceğini anlat."
            ),
            input=history[-40:]
        )

        reply = response.output_text

        history.append({
            "role": "assistant",
            "content": reply
        })

        save_json_file(MEMORY_FILE, history)

        return jsonify({
            "reply": reply
        })

    except Exception as error:
        print("OpenAI bağlantı hatası:", error)

        return jsonify({
            "reply": (
                "Yapay zeka servisine şu anda "
                "bağlanamadım."
            )
        }), 500


# ==========================================
# ANDROID GOREV LISTESI API
# ==========================================

@app.route("/tasks", methods=["GET"])
def list_tasks_api():
    with task_lock:
        result = []

        for index, task in enumerate(tasks, 1):
            result.append({
                "number": index,
                "id": get_id(task),
                "text": get_text(task),
                "due_at": get_due(task),
                "done": is_done(task),
                "repeat_interval": (
                    task.get("repeat_interval", 0)
                    if isinstance(task, dict)
                    else 0
                )
            })

    return jsonify({
        "success": True,
        "tasks": result
    })


# ==========================================
# GOREV TAMAMLAMA API
# ==========================================

@app.route(
    "/tasks/<task_id>/complete",
    methods=["POST"]
)
def complete_task_by_id_api(task_id):
    with task_lock:
        matches = [
            task for task in tasks
            if get_id(task) == task_id
        ]

        if len(matches) != 1:
            return jsonify({
                "success": False,
                "reply": "Görev bulunamadı."
            }), 404

        task = matches[0]

        if not isinstance(task, dict):
            return jsonify({
                "success": False,
                "reply": "Görev biçimi desteklenmiyor."
            }), 400

        if is_done(task):
            return jsonify({
                "success": True,
                "reply": "Görev zaten tamamlanmış.",
                "alarm_id": get_id(task),
                "due_at": get_due(task)
            })

        task["done"] = True
        task["notified"] = True

        try:
            save_tasks()
        except Exception:
            task["done"] = False
            task["notified"] = False
            return jsonify({
                "success": False,
                "reply": "Görev kaydedilemedi."
            }), 500

        return jsonify({
            "success": True,
            "reply": "Görev tamamlandı.",
            "alarm_id": get_id(task),
            "due_at": get_due(task),
            "task_text": get_text(task)
        })


# ==========================================
# GOREV SILME API
# ==========================================

@app.route(
    "/tasks/<task_id>/delete",
    methods=["POST"]
)
def delete_task_by_id_api(task_id):
    with task_lock:
        matches = [
            (i, task)
            for i, task in enumerate(tasks)
            if get_id(task) == task_id
        ]

        if len(matches) != 1:
            return jsonify({
                "success": False,
                "reply": "Görev bulunamadı."
            }), 404

        index, task = matches[0]
        removed = tasks.pop(index)

        try:
            save_tasks()
        except Exception:
            tasks.insert(index, removed)
            return jsonify({
                "success": False,
                "reply": "Görev silinemedi."
            }), 500

        return jsonify({
            "success": True,
            "reply": "Görev silindi.",
            "alarm_id": get_id(removed),
            "due_at": get_due(removed),
            "task_text": get_text(removed)
        })


# ==========================================
# ZORO SUNUCUSU
# ==========================================


# ZORO_TASK_EDIT_API
# ZORO_TASK_EDIT_V2
# Gorev ID ve tekrar bilgisi korunur.
# Ad, tarih ve saat degistirilebilir.

@app.route("/tasks/<task_id>/edit", methods=["POST"])
def edit_task_by_id_api(task_id):
    from datetime import datetime as zoro_datetime
    from zoneinfo import ZoneInfo as ZoroZoneInfo

    data = request.get_json(silent=True)

    if not isinstance(data, dict):
        return jsonify({
            "success": False,
            "reply": "Gecersiz istek."
        }), 400

    new_text = data.get("text")
    new_due = data.get("due_at")

    if not isinstance(new_text, str):
        return jsonify({
            "success": False,
            "reply": "Gorev metni gerekli."
        }), 400

    new_text = new_text.strip()

    if not new_text or len(new_text) > 500:
        return jsonify({
            "success": False,
            "reply": "Gorev metni 1-500 karakter olmali."
        }), 400

    normalized_due = None

    if new_due is not None:
        if not isinstance(new_due, str):
            return jsonify({
                "success": False,
                "reply": "Tarih bicimi gecersiz."
            }), 400

        try:
            parsed = zoro_datetime.fromisoformat(
                new_due.strip().replace("Z", "+00:00")
            )

            istanbul = ZoroZoneInfo(
                "Europe/Istanbul"
            )

            if parsed.tzinfo is None:
                parsed = parsed.replace(
                    tzinfo=istanbul
                )

            parsed = parsed.astimezone(istanbul)

            if parsed <= zoro_datetime.now(istanbul):
                return jsonify({
                    "success": False,
                    "reply": "Gecmis bir saate gorev konulamaz."
                }), 400

            normalized_due = parsed.isoformat(
                timespec="seconds"
            )

        except (ValueError, OverflowError):
            return jsonify({
                "success": False,
                "reply": "Tarih veya saat gecersiz."
            }), 400

    with task_lock:
        matches = [
            task for task in tasks
            if get_id(task) == task_id
        ]

        if len(matches) != 1:
            return jsonify({
                "success": False,
                "reply": "Gorev bulunamadi."
            }), 404

        task = matches[0]

        if not isinstance(task, dict):
            return jsonify({
                "success": False,
                "reply": "Gorev bicimi desteklenmiyor."
            }), 400

        previous = task.copy()

        try:
            task["text"] = new_text

            if "task_text" in task:
                task["task_text"] = new_text

            if normalized_due is not None:
                task["due_at"] = normalized_due
                task["notified"] = False

            save_tasks()

        except Exception:
            task.clear()
            task.update(previous)

            return jsonify({
                "success": False,
                "reply": "Gorev kaydedilemedi."
            }), 500

        return jsonify({
            "success": True,
            "reply": "Gorev guncellendi.",
            "task_id": get_id(task),
            "text": get_text(task),
            "due_at": get_due(task),
            "repeat_interval": task.get(
                "repeat_interval", 0
            )
        })

if __name__ == "__main__":
    print("ZORO SUNUCUSU BASLATILIYOR")
    print("Adres: http://0.0.0.0:5050")

    app.run(
        host="0.0.0.0",
        port=5050,
        debug=False
    )
