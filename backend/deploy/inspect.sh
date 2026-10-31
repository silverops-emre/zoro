#!/usr/bin/env bash
# Zoro VPS — READ-ONLY inspection. Changes nothing. Prints no secret values.
# Run on the server:  bash inspect.sh  > zoro-inspect.txt
set -u

section() { printf '\n===== %s =====\n' "$1"; }

section "system"
uname -a; . /etc/os-release 2>/dev/null && echo "$PRETTY_NAME"; date -Is; uptime
python3 --version 2>&1

section "services mentioning zoro / gunicorn / flask"
systemctl list-units --type=service --all --no-pager 2>/dev/null | grep -i -E 'zoro|gunicorn|flask' || echo "(none by name)"
for unit in $(systemctl list-units --type=service --all --no-legend --no-pager 2>/dev/null | awk '{print $1}' | grep -i -E 'zoro|gunicorn|flask'); do
  section "unit $unit"
  systemctl cat "$unit" --no-pager 2>/dev/null | grep -v -i -E 'password|secret|key='
  systemctl show "$unit" -p ActiveState -p SubState -p ExecMainStartTimestamp -p WorkingDirectory --no-pager
done

section "processes"
ps -eo pid,user,etime,args | grep -E 'gunicorn|app\.py|flask|caddy' | grep -v grep

section "listening ports"
ss -ltnp 2>/dev/null | grep -E ':(80|443|5050|8000|8080)\b' || ss -ltn

section "caddy"
for f in /etc/caddy/Caddyfile /root/Caddyfile; do
  [ -f "$f" ] && { echo "--- $f"; sed -E 's/(\$2[aby]\$[0-9]+\$)[^ ]+/\1<hash hidden>/g' "$f"; }
done

section "app directories (guess from units / common paths)"
dirs=$(systemctl show $(systemctl list-units --type=service --all --no-legend --no-pager 2>/dev/null | awk '{print $1}' | grep -i -E 'zoro|gunicorn') -p WorkingDirectory --value 2>/dev/null | sort -u)
for d in $dirs /root/zoro /root/Zoro /opt/zoro /srv/zoro /home/*/zoro /root/Python_ZoroBackend; do
  [ -d "$d" ] || continue
  echo "--- $d"
  ls -la "$d" | grep -v -E '\.pyc$'
  [ -f "$d/app.py" ] && { echo "app.py sha256: $(sha256sum "$d/app.py" | cut -c1-64)"; echo "app.py lines: $(wc -l < "$d/app.py")"; grep -n -E '^MODEL_NAME|app\.run|^@app\.route|basic|auth' "$d/app.py" | head -20; }
  [ -f "$d/.env" ] && { echo ".env keys (values hidden):"; sed -E 's/=.*/=<hidden>/' "$d/.env"; }
  for j in zoro_memory.json zoro_tasks.json zoro_personal_memory.json zoro.db; do
    [ -f "$d/$j" ] && echo "$j: $(stat -c '%s bytes, modified %y' "$d/$j")"
  done
  for v in "$d/venv" "$d/.venv"; do
    [ -x "$v/bin/pip" ] && { echo "venv $v:"; "$v/bin/pip" freeze 2>/dev/null | grep -i -E 'flask|openai|gunicorn|dotenv|httpx'; }
  done
done

section "known v1 (23 Oct handoff) app.py sha256"
echo "65f702a10b660cb74f1b66e308350594ce5e6383eadd1af30bd2fc97563eec29"
echo "If the server's app.py hash differs, the server has changes we do not have. Copy it back before deploying."

section "health"
curl -s -m 5 http://127.0.0.1:5050/health; echo
