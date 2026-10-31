#!/usr/bin/env bash
# Restore a backup made by deploy.sh.
#   APP_DIR=/root/zoro SERVICE=zoro.service BACKUP=/root/zoro-backups/zoro-YYYYmmdd-HHMMSS.tgz bash rollback.sh
# Note: tasks/memories created while v2 ran live in zoro.db; v1 reads only the
# JSON files, so they will not show up after a rollback (zoro.db is kept).
set -euo pipefail
: "${APP_DIR:?}"; : "${SERVICE:?}"; : "${BACKUP:?}"
PORT="${PORT:-5050}"
SYSTEMCTL="${SYSTEMCTL:-systemctl}"
[ -f "$BACKUP" ] || { echo "yedek yok: $BACKUP"; exit 1; }
rm -rf "$APP_DIR/zoro"
tar xzf "$BACKUP" -C "$APP_DIR"
$SYSTEMCTL restart "$SERVICE"
for _ in $(seq 1 15); do
  code="$(curl -s -o /dev/null -m 3 -w '%{http_code}' "http://127.0.0.1:$PORT/tasks" || true)"
  [ "$code" = "200" ] && { echo "geri alındı, /tasks 200"; exit 0; }
  sleep 1
done
echo "geri alındı ama /tasks cevap vermiyor: journalctl -u $SERVICE -n 50"; exit 1
