#!/usr/bin/env bash
set -euo pipefail

echo "start backend:"
echo "  cd backend && mvn spring-boot:run"
echo "start uni-app:"
echo "  cd frontend-mobile && npm install && npm run dev:h5"
