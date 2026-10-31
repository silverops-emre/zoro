#!/usr/bin/env bash
# Build the backend release tarball to copy to the server (no data, no secrets, no tests).
#   bash backend/deploy/make-release.sh   -> dist/zoro2-backend-<git sha>.tgz
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SHA="$(git -C "$ROOT" rev-parse --short HEAD)"
OUT="$ROOT/dist/zoro2-backend-$SHA.tgz"
mkdir -p "$ROOT/dist"
tar czf "$OUT" -C "$ROOT/backend" \
  --exclude='__pycache__' --exclude='*.pyc' \
  app.py requirements.txt .env.example zoro deploy
echo "$OUT"
tar tzf "$OUT" | sed 's/^/  /'
