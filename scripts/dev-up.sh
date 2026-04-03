#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

mkdir -p .logs .pids
mkdir -p .m2repo

if [ -f ".env" ]; then
  set -a
  . "$ROOT_DIR/.env"
  set +a
fi

docker compose up -d mysql redis qdrant langchain-runtime

BACKEND_PORT="${BACKEND_PORT:-8080}"
FRONTEND_PORT="${FRONTEND_PORT:-5173}"
QDRANT_HTTP_PORT="${QDRANT_HTTP_PORT:-6333}"
LANGCHAIN_PORT="${LANGCHAIN_PORT:-18080}"

export DB_HOST="${DB_HOST:-localhost}"
export DB_PORT="${DB_PORT:-3306}"
export DB_NAME="${DB_NAME:-smartark}"
export DB_USER="${DB_USER:-smartark}"
export DB_PASSWORD="${DB_PASSWORD:-smartark}"

export REDIS_HOST="${REDIS_HOST:-localhost}"
export REDIS_PORT="${REDIS_PORT:-6379}"
export QDRANT_HOST="${QDRANT_HOST:-localhost}"
export QDRANT_GRPC_PORT="${QDRANT_GRPC_PORT:-6334}"

export JWT_SECRET="${JWT_SECRET:-change-me}"
export JWT_TTL_SECONDS="${JWT_TTL_SECONDS:-604800}"
export MAVEN_REPO_LOCAL="${MAVEN_REPO_LOCAL:-$ROOT_DIR/.m2repo}"

export MODEL_BASE_URL="${MODEL_BASE_URL:-}"
export MODEL_API_KEY="${MODEL_API_KEY:-}"
if [ -z "${MODEL_API_KEY}" ]; then
  export MODEL_MOCK_ENABLED="${MODEL_MOCK_ENABLED:-true}"
else
  export MODEL_MOCK_ENABLED="${MODEL_MOCK_ENABLED:-false}"
fi
export CHAT_MODEL="${CHAT_MODEL:-qwen-plus}"
export CODE_MODEL="${CODE_MODEL:-qwen-plus}"
export LANGCHAIN_ENABLED="${LANGCHAIN_ENABLED:-false}"
export LANGCHAIN_SIDECAR_BASE_URL="${LANGCHAIN_SIDECAR_BASE_URL:-http://localhost:${LANGCHAIN_PORT}}"
export LANGCHAIN_SIDECAR_TIMEOUT_MS="${LANGCHAIN_SIDECAR_TIMEOUT_MS:-3000}"
export LANGCHAIN_SIDECAR_API_VERSION="${LANGCHAIN_SIDECAR_API_VERSION:-v1}"
export LANGCHAIN_RUNTIME_MODEL_ENABLED="${LANGCHAIN_RUNTIME_MODEL_ENABLED:-false}"
export LANGCHAIN_RUNTIME_BASE_URL="${LANGCHAIN_RUNTIME_BASE_URL:-http://localhost:${LANGCHAIN_PORT}}"
export LANGCHAIN_RUNTIME_TIMEOUT_MS="${LANGCHAIN_RUNTIME_TIMEOUT_MS:-45000}"
export LANGCHAIN_RUNTIME_CODEGEN_GRAPH_ENABLED="${LANGCHAIN_RUNTIME_CODEGEN_GRAPH_ENABLED:-false}"
export LANGCHAIN_RUNTIME_PAPER_GRAPH_ENABLED="${LANGCHAIN_RUNTIME_PAPER_GRAPH_ENABLED:-false}"

echo "waiting for qdrant http://localhost:${QDRANT_HTTP_PORT}/healthz"
for _ in $(seq 1 60); do
  if curl -fsS "http://localhost:${QDRANT_HTTP_PORT}/healthz" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

echo "waiting for langchain-runtime http://localhost:${LANGCHAIN_PORT}/health"
for _ in $(seq 1 60); do
  if curl -fsS "http://localhost:${LANGCHAIN_PORT}/health" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

if [ -f ".pids/backend.pid" ] && kill -0 "$(cat .pids/backend.pid)" 2>/dev/null; then
  echo "backend already running (pid=$(cat .pids/backend.pid))"
else
  (
    cd "$ROOT_DIR/services/api-gateway-java"
    # Prefer Maven Wrapper if available, fallback to system mvn
    if [ -x "./mvnw" ]; then
      MVN_CMD="./mvnw"
    elif command -v mvn >/dev/null 2>&1; then
      MVN_CMD="mvn"
    else
      echo "ERROR: neither ./mvnw nor mvn found on PATH" >&2
      exit 1
    fi
    nohup $MVN_CMD -Dmaven.repo.local="${MAVEN_REPO_LOCAL}" -Dmaven.test.skip=true spring-boot:run >"$ROOT_DIR/.logs/backend.log" 2>&1 &
    echo $! >"$ROOT_DIR/.pids/backend.pid"
  )
fi

echo "waiting for backend http://localhost:${BACKEND_PORT}/actuator/health"
for _ in $(seq 1 60); do
  if curl -fsS "http://localhost:${BACKEND_PORT}/actuator/health" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

if [ -f ".pids/frontend.pid" ] && kill -0 "$(cat .pids/frontend.pid)" 2>/dev/null; then
  echo "frontend already running (pid=$(cat .pids/frontend.pid))"
else
  (
    cd "$ROOT_DIR/frontend-web"
    if [ ! -d "node_modules" ]; then
      echo "installing frontend dependencies..."
      npm install >"$ROOT_DIR/.logs/frontend.install.log" 2>&1
    fi
    nohup npm run dev -- --host 0.0.0.0 --port "${FRONTEND_PORT}" >"$ROOT_DIR/.logs/frontend.log" 2>&1 &
    echo $! >"$ROOT_DIR/.pids/frontend.pid"
  )
fi

echo "waiting for frontend http://localhost:${FRONTEND_PORT}/"
for _ in $(seq 1 60); do
  if curl -fsS "http://localhost:${FRONTEND_PORT}/" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

echo "backend:  http://localhost:${BACKEND_PORT}"
echo "frontend: http://localhost:${FRONTEND_PORT}"
