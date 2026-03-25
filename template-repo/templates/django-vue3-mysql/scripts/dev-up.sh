#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

docker compose up -d mysql

echo "start backend:"
echo "  cd backend && python -m venv .venv && .venv/Scripts/pip install -r requirements.txt && .venv/Scripts/python manage.py migrate && .venv/Scripts/python manage.py runserver 0.0.0.0:8000"
echo "start frontend:"
echo "  cd frontend && npm install && npm run dev"
