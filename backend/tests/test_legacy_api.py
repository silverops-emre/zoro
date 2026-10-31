"""The v1 Android app's task screen must keep working unchanged."""

import json

from conftest import Harness, at
from zoro.timeutil import DAILY_MS


def test_list_complete_edit_delete_shapes(h):
    task = h.store.add_task("Rapor", at(1, 11), 0)
    body = h.client.get("/tasks").get_json()
    assert body["success"] is True
    assert set(body["tasks"][0]) == {"number", "id", "text", "due_at", "done", "repeat_interval"}

    # The app sends yyyy-MM-ddTHH:mm without offset from the edit dialog.
    new_due = at(2, 16)[:16]
    r = h.client.post(f"/tasks/{task['id']}/edit", json={"text": "Rapor v2", "due_at": new_due})
    data = r.get_json()
    assert r.status_code == 200 and data["success"] is True
    assert data["due_at"].startswith(new_due) and data["due_at"].endswith("+03:00")
    # The app closes its dialog when it sees this exact flow; keep the text.
    assert data["reply"] == "Gorev guncellendi."

    r = h.client.post(f"/tasks/{task['id']}/complete", json={})
    assert r.get_json()["success"] is True and r.get_json()["alarm_id"] == task["id"]
    r = h.client.post(f"/tasks/{task['id']}/complete", json={})
    assert r.get_json()["reply"] == "Görev zaten tamamlanmış."

    r = h.client.post(f"/tasks/{task['id']}/delete", json={})
    assert r.get_json()["success"] is True
    assert h.client.post(f"/tasks/{task['id']}/delete", json={}).status_code == 404


def test_edit_rejects_past_and_garbage(h):
    task = h.store.add_task("x", at(1, 11), 0)
    assert h.client.post(f"/tasks/{task['id']}/edit", json={"text": "x", "due_at": at(-1, 9)}).status_code == 400
    assert h.client.post(f"/tasks/{task['id']}/edit", json={"text": "x", "due_at": "yarın"}).status_code == 400
    assert h.client.post(f"/tasks/{task['id']}/edit", json={"text": ""}).status_code == 400
    assert h.client.post("/tasks/nope/edit", json={"text": "x"}).status_code == 404


def test_legacy_json_is_imported_once(tmp_path):
    (tmp_path / "zoro_memory.json").write_text(json.dumps([
        {"role": "user", "content": "merhaba"}, {"role": "assistant", "content": "selam"}, {"role": "x", "content": 1},
    ]), encoding="utf-8")
    (tmp_path / "zoro_tasks.json").write_text(json.dumps([
        {"id": "aa11", "text": "Su iç", "done": False, "due_at": at(1, 9), "notified": False, "repeat_interval": DAILY_MS},
        {"id": "bb22", "text": "Eski", "done": True, "due_at": None, "notified": True, "repeat_interval": 0},
    ]), encoding="utf-8")
    (tmp_path / "zoro_personal_memory.json").write_text(json.dumps([
        {"key": "ugurlu sayim", "value": "7"},
    ]), encoding="utf-8")

    h = Harness(tmp_path)
    assert h.store.count_messages() == 2
    tasks = h.store.list_tasks()
    assert [t["id"] for t in tasks] == ["aa11", "bb22"]
    assert tasks[0]["repeat_interval"] == DAILY_MS and tasks[1]["done"] is True
    assert h.store.list_facts()[0]["text"] == "ugurlu sayim: 7"

    again = Harness(tmp_path)  # a second worker / restart
    assert again.store.count_messages() == 2 and len(again.store.list_tasks()) == 2
    # originals untouched
    assert json.loads((tmp_path / "zoro_tasks.json").read_text())[0]["id"] == "aa11"


def test_v1_ask_body_still_accepted(h):
    from conftest import resp
    h.script(resp("Merhaba Zafer Bey."))
    r = h.client.post("/ask", data=json.dumps({"message": "merhaba"}), content_type="application/json; charset=utf-8")
    assert r.status_code == 200
    body = r.get_json()
    assert body["reply"] == "Merhaba Zafer Bey." and body["action"] == ""


def test_unknown_route_and_bad_json(h):
    assert h.client.get("/nope").status_code == 404
    r = h.client.post("/ask", data="{bozuk", content_type="application/json")
    assert r.status_code == 400 and r.get_json()["reply"]
