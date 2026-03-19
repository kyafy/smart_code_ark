#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

stop_pid() {
  local pid_file="$1"
  if [ -f "$pid_file" ]; then
    local pid
    pid="$(cat "$pid_file" || true)"
    if [ -n "${pid}" ] && kill -0 "$pid" 2>/dev/null; then
      kill "$pid" || true
    fi
    rm -f "$pid_file"
  fi
}

stop_pid "$ROOT_DIR/.pids/frontend.pid"
stop_pid "$ROOT_DIR/.pids/backend.pid"

docker compose stop mysql redis >/dev/null 2>&1 || true
