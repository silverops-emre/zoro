"""SQLite storage for messages, tasks, facts and pending confirmations.

One file, WAL mode, a short-lived connection per operation. Safe across
gunicorn workers and threads (the v1 JSON globals were not: each worker kept
its own copy and overwrote the others on save).
"""

from __future__ import annotations

import json
import sqlite3
import uuid
from contextlib import contextmanager
from datetime import datetime
from pathlib import Path
from typing import Any, Iterator

SCHEMA = """
CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS messages (
    seq        INTEGER PRIMARY KEY AUTOINCREMENT,
    role       TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
    content    TEXT NOT NULL,
    actions    TEXT,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS tasks (
    seq             INTEGER PRIMARY KEY AUTOINCREMENT,
    id              TEXT NOT NULL UNIQUE,
    text            TEXT NOT NULL,
    done            INTEGER NOT NULL DEFAULT 0,
    due_at          TEXT,
    notified        INTEGER NOT NULL DEFAULT 0,
    repeat_interval INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS facts (
    seq        INTEGER PRIMARY KEY AUTOINCREMENT,
    id         TEXT NOT NULL UNIQUE,
    key        TEXT,
    text       TEXT NOT NULL,
    source     TEXT NOT NULL DEFAULT 'zoro',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS pending (
    id         TEXT PRIMARY KEY,
    kind       TEXT NOT NULL,
    payload    TEXT NOT NULL,
    summary    TEXT NOT NULL,
    status     TEXT NOT NULL DEFAULT 'pending',
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS usage (
    seq           INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at    TEXT NOT NULL,
    month         TEXT NOT NULL,
    feature       TEXT NOT NULL,
    model         TEXT NOT NULL,
    input_tokens  INTEGER NOT NULL DEFAULT 0,
    cached_tokens INTEGER NOT NULL DEFAULT 0,
    output_tokens INTEGER NOT NULL DEFAULT 0,
    searches      INTEGER NOT NULL DEFAULT 0,
    cost_usd      REAL,
    turn_id       TEXT
);
CREATE INDEX IF NOT EXISTS usage_month ON usage(month);
"""

TASK_FIELDS = "id, text, done, due_at, notified, repeat_interval, created_at"


def _now_iso() -> str:
    return datetime.now().astimezone().isoformat(timespec="seconds")


def _task_row(row: sqlite3.Row) -> dict[str, Any]:
    return {
        "id": row["id"],
        "text": row["text"],
        "done": bool(row["done"]),
        "due_at": row["due_at"],
        "notified": bool(row["notified"]),
        "repeat_interval": int(row["repeat_interval"] or 0),
        "created_at": row["created_at"],
    }


def _fact_row(row: sqlite3.Row) -> dict[str, Any]:
    return {
        "id": row["id"],
        "key": row["key"],
        "text": row["text"],
        "source": row["source"],
        "created_at": row["created_at"],
        "updated_at": row["updated_at"],
    }


class Store:
    def __init__(self, path: Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as conn:
            conn.execute("PRAGMA journal_mode=WAL")
            conn.executescript(SCHEMA)

    @contextmanager
    def connect(self) -> Iterator[sqlite3.Connection]:
        conn = sqlite3.connect(self.path, timeout=10)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys=ON")
        conn.execute("PRAGMA busy_timeout=10000")
        try:
            yield conn
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    # ---------------------------------------------------------------- meta
    def get_meta(self, key: str) -> str | None:
        with self.connect() as conn:
            row = conn.execute("SELECT value FROM meta WHERE key = ?", (key,)).fetchone()
        return row["value"] if row else None

    def set_meta(self, key: str, value: str) -> None:
        with self.connect() as conn:
            conn.execute(
                "INSERT INTO meta(key, value) VALUES(?, ?) "
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                (key, value),
            )

    def update_json_meta(self, key: str, update: Any) -> dict[str, Any]:
        """Read-modify-write of a JSON object in meta, atomic across workers."""
        conn = sqlite3.connect(self.path, timeout=10, isolation_level=None)
        try:
            conn.execute("PRAGMA busy_timeout=10000")
            conn.execute("BEGIN IMMEDIATE")
            row = conn.execute("SELECT value FROM meta WHERE key = ?", (key,)).fetchone()
            try:
                current = json.loads(row[0]) if row else {}
            except ValueError:
                current = {}
            new = update(current if isinstance(current, dict) else {})
            conn.execute(
                "INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                (key, json.dumps(new, ensure_ascii=False)),
            )
            conn.execute("COMMIT")
            return new
        except Exception:
            if conn.in_transaction:
                conn.execute("ROLLBACK")
            raise
        finally:
            conn.close()

    # ------------------------------------------------------------ messages
    def add_message(self, role: str, content: str, actions: list | None = None) -> None:
        with self.connect() as conn:
            conn.execute(
                "INSERT INTO messages(role, content, actions, created_at) VALUES(?, ?, ?, ?)",
                (role, content, json.dumps(actions, ensure_ascii=False) if actions else None, _now_iso()),
            )

    def recent_messages(self, limit: int) -> list[dict[str, Any]]:
        if limit <= 0:
            return []
        with self.connect() as conn:
            rows = conn.execute(
                "SELECT role, content, actions FROM messages ORDER BY seq DESC LIMIT ?", (limit,)
            ).fetchall()
        result = []
        for row in reversed(rows):
            result.append({
                "role": row["role"],
                "content": row["content"],
                "actions": json.loads(row["actions"]) if row["actions"] else None,
            })
        return result

    def count_messages(self) -> int:
        with self.connect() as conn:
            return conn.execute("SELECT COUNT(*) FROM messages").fetchone()[0]

    # --------------------------------------------------------------- tasks
    def list_tasks(self, pending_only: bool = False) -> list[dict[str, Any]]:
        sql = f"SELECT {TASK_FIELDS} FROM tasks"
        if pending_only:
            sql += " WHERE done = 0"
        sql += " ORDER BY seq"
        with self.connect() as conn:
            return [_task_row(r) for r in conn.execute(sql).fetchall()]

    def get_task(self, task_id: str) -> dict[str, Any] | None:
        with self.connect() as conn:
            row = conn.execute(f"SELECT {TASK_FIELDS} FROM tasks WHERE id = ?", (task_id,)).fetchone()
        return _task_row(row) if row else None

    def find_tasks_by_prefix(self, prefix: str) -> list[dict[str, Any]]:
        prefix = (prefix or "").strip()
        if not prefix:
            return []
        escaped = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        with self.connect() as conn:
            rows = conn.execute(
                f"SELECT {TASK_FIELDS} FROM tasks WHERE id LIKE ? ESCAPE '\\' ORDER BY seq",
                (escaped + "%",),
            ).fetchall()
        return [_task_row(r) for r in rows]

    def add_task(self, text: str, due_at: str | None, repeat_interval: int = 0, task_id: str | None = None) -> dict[str, Any]:
        task_id = task_id or uuid.uuid4().hex
        with self.connect() as conn:
            conn.execute(
                "INSERT INTO tasks(id, text, done, due_at, notified, repeat_interval, created_at) "
                "VALUES(?, ?, 0, ?, 0, ?, ?)",
                (task_id, text, due_at, int(repeat_interval or 0), _now_iso()),
            )
        task = self.get_task(task_id)
        assert task is not None
        return task

    def update_task(self, task_id: str, **fields: Any) -> dict[str, Any] | None:
        allowed = {"text", "done", "due_at", "notified", "repeat_interval"}
        updates = {k: v for k, v in fields.items() if k in allowed}
        if updates:
            columns = ", ".join(f"{k} = ?" for k in updates)
            values = [int(v) if isinstance(v, bool) else v for v in updates.values()]
            with self.connect() as conn:
                conn.execute(f"UPDATE tasks SET {columns} WHERE id = ?", (*values, task_id))
        return self.get_task(task_id)

    def delete_task(self, task_id: str) -> dict[str, Any] | None:
        task = self.get_task(task_id)
        if task is None:
            return None
        with self.connect() as conn:
            conn.execute("DELETE FROM tasks WHERE id = ?", (task_id,))
        return task

    def clear_tasks(self) -> list[dict[str, Any]]:
        tasks = self.list_tasks()
        with self.connect() as conn:
            conn.execute("DELETE FROM tasks")
        return tasks

    # --------------------------------------------------------------- facts
    def list_facts(self) -> list[dict[str, Any]]:
        with self.connect() as conn:
            rows = conn.execute("SELECT * FROM facts ORDER BY seq").fetchall()
        return [_fact_row(r) for r in rows]

    def get_fact(self, fact_id: str) -> dict[str, Any] | None:
        with self.connect() as conn:
            row = conn.execute("SELECT * FROM facts WHERE id = ?", (fact_id,)).fetchone()
        return _fact_row(row) if row else None

    def upsert_fact(self, text: str, key: str | None = None, source: str = "zoro") -> tuple[dict[str, Any], bool]:
        """Insert a fact, or update the one with the same key / same text.

        Returns (fact, created).
        """
        text = " ".join(str(text).split())
        key = " ".join(str(key).split()).lower() if key else None
        now = _now_iso()
        with self.connect() as conn:
            row = None
            if key:
                row = conn.execute("SELECT * FROM facts WHERE key = ?", (key,)).fetchone()
            if row is None:
                row = conn.execute(
                    "SELECT * FROM facts WHERE lower(text) = lower(?)", (text,)
                ).fetchone()
            if row is not None:
                conn.execute(
                    "UPDATE facts SET text = ?, key = COALESCE(?, key), updated_at = ? WHERE id = ?",
                    (text, key, now, row["id"]),
                )
                fact_id, created = row["id"], False
            else:
                fact_id, created = uuid.uuid4().hex, True
                conn.execute(
                    "INSERT INTO facts(id, key, text, source, created_at, updated_at) VALUES(?, ?, ?, ?, ?, ?)",
                    (fact_id, key, text, source, now, now),
                )
        fact = self.get_fact(fact_id)
        assert fact is not None
        return fact, created

    def delete_fact(self, fact_id: str) -> dict[str, Any] | None:
        fact = self.get_fact(fact_id)
        if fact is None:
            return None
        with self.connect() as conn:
            conn.execute("DELETE FROM facts WHERE id = ?", (fact_id,))
        return fact

    # ------------------------------------------------------------- pending
    def create_pending(self, kind: str, payload: dict, summary: str, now: datetime, ttl_s: int) -> dict[str, Any]:
        pending_id = uuid.uuid4().hex[:12]
        expires = datetime.fromtimestamp(now.timestamp() + ttl_s, tz=now.tzinfo)
        with self.connect() as conn:
            # Only one open question at a time: a new one supersedes the old.
            conn.execute("UPDATE pending SET status = 'superseded' WHERE status = 'pending'")
            conn.execute(
                "INSERT INTO pending(id, kind, payload, summary, status, created_at, expires_at) "
                "VALUES(?, ?, ?, ?, 'pending', ?, ?)",
                (pending_id, kind, json.dumps(payload, ensure_ascii=False), summary,
                 now.isoformat(timespec="seconds"), expires.isoformat(timespec="seconds")),
            )
        return {"id": pending_id, "kind": kind, "payload": payload, "summary": summary,
                "expires_at": expires.isoformat(timespec="seconds")}

    def active_pending(self, now: datetime) -> dict[str, Any] | None:
        with self.connect() as conn:
            row = conn.execute(
                "SELECT * FROM pending WHERE status = 'pending' ORDER BY created_at DESC LIMIT 1"
            ).fetchone()
            if row is None:
                return None
            if datetime.fromisoformat(row["expires_at"]) <= now:
                conn.execute("UPDATE pending SET status = 'expired' WHERE id = ?", (row["id"],))
                return None
        return {"id": row["id"], "kind": row["kind"], "payload": json.loads(row["payload"]),
                "summary": row["summary"], "expires_at": row["expires_at"]}

    def close_pending(self, pending_id: str, status: str) -> bool:
        """Atomically move a pending row out of 'pending'. False if already closed."""
        with self.connect() as conn:
            cur = conn.execute(
                "UPDATE pending SET status = ? WHERE id = ? AND status = 'pending'", (status, pending_id)
            )
            return cur.rowcount == 1

    # --------------------------------------------------------------- usage
    def add_usage(self, *, created_at: datetime, feature: str, model: str, input_tokens: int = 0,
                  cached_tokens: int = 0, output_tokens: int = 0, searches: int = 0,
                  cost_usd: float | None = None, turn_id: str | None = None) -> None:
        """One metered OpenAI call. cost_usd None = model price unknown."""
        with self.connect() as conn:
            conn.execute(
                "INSERT INTO usage(created_at, month, feature, model, input_tokens, cached_tokens, "
                "output_tokens, searches, cost_usd, turn_id) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (created_at.isoformat(timespec="seconds"), created_at.strftime("%Y-%m"), feature, model,
                 int(input_tokens), int(cached_tokens), int(output_tokens), int(searches), cost_usd, turn_id),
            )

    def usage_rows(self, month: str) -> list[dict[str, Any]]:
        with self.connect() as conn:
            rows = conn.execute(
                "SELECT created_at, feature, model, input_tokens, cached_tokens, output_tokens, searches, "
                "cost_usd, turn_id FROM usage WHERE month = ? ORDER BY seq", (month,)
            ).fetchall()
        return [dict(row) for row in rows]

    def usage_since(self, since: datetime) -> list[dict[str, Any]]:
        with self.connect() as conn:
            rows = conn.execute(
                "SELECT created_at, feature, turn_id FROM usage WHERE created_at >= ? ORDER BY seq",
                (since.isoformat(timespec="seconds"),),
            ).fetchall()
        return [dict(row) for row in rows]

    def month_cost(self, month: str) -> float:
        with self.connect() as conn:
            value = conn.execute("SELECT COALESCE(SUM(cost_usd), 0) FROM usage WHERE month = ?", (month,)).fetchone()[0]
        return float(value or 0)

    # ------------------------------------------------------- legacy import
    def import_legacy(self, history_file: Path, tasks_file: Path, personal_file: Path) -> dict[str, int]:
        """Import v1 JSON files once. Original files are left untouched."""
        if self.get_meta("legacy_imported"):
            return {}
        # Several gunicorn workers start at once: only the one that wins this
        # INSERT imports, the others skip.
        with self.connect() as conn:
            claimed = conn.execute(
                "INSERT OR IGNORE INTO meta(key, value) VALUES('legacy_import_claim', ?)", (_now_iso(),)
            ).rowcount == 1
        if not claimed:
            return {}

        def load(path: Path) -> list:
            try:
                value = json.loads(Path(path).read_text(encoding="utf-8"))
                return value if isinstance(value, list) else []
            except (OSError, ValueError):
                return []

        counts = {"messages": 0, "tasks": 0, "facts": 0}
        for item in load(history_file):
            if isinstance(item, dict) and item.get("role") in ("user", "assistant") and isinstance(item.get("content"), str):
                self.add_message(item["role"], item["content"])
                counts["messages"] += 1
        for item in load(tasks_file):
            if not isinstance(item, dict) or not str(item.get("text", "")).strip():
                continue
            task_id = str(item.get("id") or item.get("alarm_id") or uuid.uuid4().hex)
            if self.get_task(task_id):
                continue
            self.add_task(str(item["text"]), item.get("due_at"), int(item.get("repeat_interval") or 0), task_id=task_id)
            self.update_task(task_id, done=bool(item.get("done")), notified=bool(item.get("notified")))
            counts["tasks"] += 1
        for item in load(personal_file):
            if isinstance(item, dict) and item.get("key") and item.get("value"):
                self.upsert_fact(f"{item['key']}: {item['value']}", key=str(item["key"]), source="v1")
                counts["facts"] += 1
        self.set_meta("legacy_imported", json.dumps(counts))
        return counts
