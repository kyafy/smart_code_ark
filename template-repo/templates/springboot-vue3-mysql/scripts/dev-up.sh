#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

docker compose up -d mysql

echo "start backend:"
echo "  cd backend && mvn spring-boot:run"
echo "start frontend:"
echo "  cd frontend && npm install && npm run dev"
