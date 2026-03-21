#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
docker compose down -v
docker compose up -d mysql
