#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

for pidfile in .pids/*.pid; do
  [ -f "$pidfile" ] || continue
  pid=$(cat "$pidfile")
  name=$(basename "$pidfile" .pid)
  if kill -0 "$pid" 2>/dev/null; then
    echo "stopping $name (pid=$pid)"
    kill "$pid" 2>/dev/null || true
  else
    echo "$name (pid=$pid) not running"
  fi
  rm -f "$pidfile"
done

echo "stopping docker services (mysql, redis)..."
docker compose stop mysql redis 2>/dev/null || true

echo "all dev services stopped"
