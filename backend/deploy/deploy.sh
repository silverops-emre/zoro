#!/usr/bin/env bash
# Zoro 2.0 backend deploy with backup and automatic rollback.
#
# Usage on the server (after inspect.sh told you the values):
#   APP_DIR=/root/zoro SERVICE=zoro.service RELEASE=/root/zoro2-release bash deploy.sh
# Optional: VENV=/root/zoro/venv PORT=5050 FORCE=1 (deploy over an unknown app.py)
#
# What it does: backup -> install deps -> dry run on a COPY of the data ->
# swap code -> restart -> health + data checks -> rollback if anything fails.
# Never touches .env or the v1 JSON files (they are imported into zoro.db).
set -euo pipefail

: "${APP_DIR:?APP_DIR gerekli (inspect.sh çıktısındaki WorkingDirectory)}"
: "${SERVICE:?SERVICE gerekli (örn. zoro.service)}"
: "${RELEASE:?RELEASE gerekli (zoro2 backend klasörünün sunucudaki yolu)}"
PORT="${PORT:-5050}"
FORCE="${FORCE:-0}"
V1_SHA="65f702a10b660cb74f1b66e308350594ce5e6383eadd1af30bd2fc97563eec29"
TS="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="${BACKUP_DIR:-/root/zoro-backups}"
WORK_DIR="${WORK_DIR:-/root}"
SYSTEMCTL="${SYSTEMCTL:-systemctl}"     # test: SYSTEMCTL="systemctl --user"
JOURNALCTL="${JOURNALCTL:-journalctl}"
BACKUP="$BACKUP_DIR/zoro-$TS.tgz"

if [ -z "${VENV:-}" ]; then
  for v in "$APP_DIR/venv" "$APP_DIR/.venv"; do [ -x "$v/bin/python" ] && VENV="$v" && break; done
fi
: "${VENV:?venv bulunamadı, VENV=... verin}"

log() { printf '[deploy %s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "HATA: $*"; exit 1; }

# ------------------------------------------------------------ preflight
[ -f "$APP_DIR/app.py" ] || die "$APP_DIR/app.py yok"
[ -f "$RELEASE/app.py" ] && [ -d "$RELEASE/zoro" ] || die "$RELEASE içinde app.py + zoro/ yok"
$SYSTEMCTL cat "$SERVICE" >/dev/null 2>&1 || die "$SERVICE servisi yok"

current_sha="$(sha256sum "$APP_DIR/app.py" | cut -c1-64)"
if grep -q "from zoro.web import create_app" "$APP_DIR/app.py"; then
  log "Sunucuda zaten v2 var, kod güncelleniyor."
elif [ "$current_sha" != "$V1_SHA" ] && [ "$FORCE" != "1" ]; then
  die "Sunucudaki app.py 23 Ekim sürümünden farklı ($current_sha). Önce o dosyayı bize alın ve farkı inceleyin. Bilerek üzerine yazmak için FORCE=1."
fi

timeout_flag="$($SYSTEMCTL cat "$SERVICE" | grep -o -E -- '--timeout[ =][0-9]+' | grep -o -E '[0-9]+' || true)"
if [ -z "$timeout_flag" ] || [ "$timeout_flag" -lt 30 ]; then
  log "Not: gunicorn --timeout ${timeout_flag:-30 (varsayılan)}. ZORO_TURN_BUDGET_V2=25 buna uygun."
fi

# --------------------------------------------------------------- backup
mkdir -p "$BACKUP_DIR"
tar czf "$BACKUP" -C "$APP_DIR" --exclude=venv --exclude=.venv --exclude=__pycache__ .
log "Yedek: $BACKUP ($(du -h "$BACKUP" | cut -f1))"

rollback() {
  log "GERİ ALINIYOR: $BACKUP"
  rm -rf "$APP_DIR/zoro"
  tar xzf "$BACKUP" -C "$APP_DIR"
  $SYSTEMCTL restart "$SERVICE"
  for _ in $(seq 1 15); do
    code="$(curl -s -o /dev/null -m 3 -w '%{http_code}' "http://127.0.0.1:$PORT/tasks" || true)"
    [ "$code" = "200" ] && { log "Eski sürüm geri geldi (/tasks 200)."; return 0; }
    sleep 1
  done
  log "DİKKAT: geri alma sonrası /tasks cevap vermiyor. $JOURNALCTL -u $SERVICE -n 50"
  return 1
}

# ------------------------------------------------------------- packages
log "Paketler kuruluyor"
if "$VENV/bin/python" -m pip --version >/dev/null 2>&1; then
  "$VENV/bin/python" -m pip install -q -r "$RELEASE/requirements.txt"
elif command -v uv >/dev/null 2>&1; then
  uv pip install -q --python "$VENV/bin/python" -r "$RELEASE/requirements.txt"
else
  die "venv'de pip yok ve uv bulunamadı"
fi

# -------------------------------------------- dry run on a copy of data
DRY="$(mktemp -d "$WORK_DIR/zoro-dryrun-XXXX")"
cp -p "$APP_DIR"/zoro_*.json "$DRY"/ 2>/dev/null || true
[ -f "$APP_DIR/zoro.db" ] && cp -p "$APP_DIR/zoro.db" "$DRY"/
log "Deneme çalıştırması ($DRY)"
"$VENV/bin/python" -m py_compile "$RELEASE/app.py" || { rm -rf "$DRY"; die "$RELEASE/app.py derlenmiyor"; }
ZORO_DATA_DIR="$DRY" "$VENV/bin/python" - "$RELEASE" <<'PY'
import json, sys
sys.path.insert(0, sys.argv[1])
from zoro.web import create_app
app = create_app()
store = app.extensions["zoro"]["store"]
c = app.test_client()
assert c.get("/health").get_json()["version"].startswith("2."), "health"
tasks = c.get("/tasks").get_json()["tasks"]
print(json.dumps({"tasks": len(tasks), "messages": store.count_messages(), "facts": len(store.list_facts())}))
PY
rm -rf "$DRY"

# ----------------------------------------------------------------- swap
log "Kod kopyalanıyor"
cp -p "$APP_DIR/app.py" "$APP_DIR/app_v1_before_$TS.py"
rm -rf "$APP_DIR/zoro"
cp -r "$RELEASE/zoro" "$APP_DIR/zoro"
cp -p "$RELEASE/app.py" "$RELEASE/requirements.txt" "$RELEASE/.env.example" "$APP_DIR/"
find "$APP_DIR/zoro" -name '__pycache__' -prune -exec rm -rf {} +

$SYSTEMCTL restart "$SERVICE"

# --------------------------------------------------------------- verify
ok=0
for _ in $(seq 1 20); do
  body="$(curl -s -m 3 "http://127.0.0.1:$PORT/health" || true)"
  case "$body" in *'"version":"2.'*) ok=1; break ;; esac
  sleep 1
done
if [ "$ok" != "1" ]; then
  log "Sağlık kontrolü başarısız: $body"
  $JOURNALCTL -u "$SERVICE" -n 30 --no-pager || true
  rollback; exit 1
fi
tasks_code="$(curl -s -o /dev/null -m 5 -w '%{http_code}' "http://127.0.0.1:$PORT/tasks")"
if [ "$tasks_code" != "200" ] && [ "$tasks_code" != "401" ]; then
  log "/tasks $tasks_code döndü"
  rollback; exit 1
fi
log "TAMAM: v2 çalışıyor. Yedek: $BACKUP"
log "Telefonda dene: 'Zoro, yarın 9'da su içmemi hatırlat' (eski uygulama da çalışmalı)."
