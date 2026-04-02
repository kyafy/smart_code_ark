#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

mkdir -p .logs .pids

if [ -f ".env" ]; then
  set -a
  . "$ROOT_DIR/.env"
  set +a
fi

docker compose up -d mysql redis qdrant jeecg-sidecar

BACKEND_PORT="${BACKEND_PORT:-8080}"
FRONTEND_PORT="${FRONTEND_PORT:-5173}"
QDRANT_HTTP_PORT="${QDRANT_HTTP_PORT:-6333}"

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

export MODEL_BASE_URL="${MODEL_BASE_URL:-}"
export MODEL_API_KEY="${MODEL_API_KEY:-}"
if [ -z "${MODEL_API_KEY}" ]; then
  export MODEL_MOCK_ENABLED="${MODEL_MOCK_ENABLED:-true}"
else
  export MODEL_MOCK_ENABLED="${MODEL_MOCK_ENABLED:-false}"
fi
export CHAT_MODEL="${CHAT_MODEL:-qwen-plus}"
export CODE_MODEL="${CODE_MODEL:-qwen-plus}"
export CODEGEN_JEECG_ENABLED="${CODEGEN_JEECG_ENABLED:-true}"
export CODEGEN_JEECG_BASE_URL="${CODEGEN_JEECG_BASE_URL:-http://localhost:19090}"
export CODEGEN_JEECG_RENDER_PATH="${CODEGEN_JEECG_RENDER_PATH:-/api/codegen/jeecg/render}"
export CODEGEN_JEECG_TIMEOUT_MS="${CODEGEN_JEECG_TIMEOUT_MS:-8000}"
export CODEGEN_INTERNAL_PROVIDER_ORDER="${CODEGEN_INTERNAL_PROVIDER_ORDER:-local_template,jeecg}"
export CODEGEN_INTERNAL_HYBRID_PROVIDER_ORDER="${CODEGEN_INTERNAL_HYBRID_PROVIDER_ORDER:-local_template,jeecg}"
export CODEGEN_INTERNAL_STRICT_PROVIDER_ORDER="${CODEGEN_INTERNAL_STRICT_PROVIDER_ORDER:-local_template,jeecg}"
export CODEGEN_LOCAL_TEMPLATE_ENABLED="${CODEGEN_LOCAL_TEMPLATE_ENABLED:-true}"
export CODEGEN_LOCAL_TEMPLATE_PATH_REWRITE_ENABLED="${CODEGEN_LOCAL_TEMPLATE_PATH_REWRITE_ENABLED:-true}"
export CODEGEN_LOCAL_TEMPLATE_CONTENT_REWRITE_ENABLED="${CODEGEN_LOCAL_TEMPLATE_CONTENT_REWRITE_ENABLED:-true}"
export CODEGEN_LOCAL_TEMPLATE_EXTENSION_REWRITE_ENABLED="${CODEGEN_LOCAL_TEMPLATE_EXTENSION_REWRITE_ENABLED:-true}"
export CODEGEN_LOCAL_TEMPLATE_OVERWRITE_TARGET="${CODEGEN_LOCAL_TEMPLATE_OVERWRITE_TARGET:-true}"
export JEECG_UPSTREAM_BASE_URL="${JEECG_UPSTREAM_BASE_URL:-http://localhost:8080/jeecg-boot}"
export JEECG_LOGIN_PATH="${JEECG_LOGIN_PATH:-/sys/login}"
export JEECG_CODEGEN_PATH="${JEECG_CODEGEN_PATH:-/online/cgform/api/codeGenerate}"
export JEECG_TOKEN_HEADER="${JEECG_TOKEN_HEADER:-X-Access-Token}"
export JEECG_TOKEN_JSON_PATH="${JEECG_TOKEN_JSON_PATH:-result.token}"
export JEECG_ACCESS_TOKEN="${JEECG_ACCESS_TOKEN:-}"
export JEECG_USERNAME="${JEECG_USERNAME:-}"
export JEECG_PASSWORD="${JEECG_PASSWORD:-}"
export JEECG_REQUEST_TIMEOUT_MS="${JEECG_REQUEST_TIMEOUT_MS:-45000}"
export RELEASE_ENABLED="${RELEASE_ENABLED:-true}"
export RELEASE_COMMAND_EXECUTION_ENABLED="${RELEASE_COMMAND_EXECUTION_ENABLED:-true}"
export RELEASE_TIMEOUT_SECONDS="${RELEASE_TIMEOUT_SECONDS:-900}"
export RELEASE_REGISTRY_PREFIX="${RELEASE_REGISTRY_PREFIX:-}"
export RELEASE_VERIFY_HEALTH_URL="${RELEASE_VERIFY_HEALTH_URL:-}"
export RELEASE_K8S_NAMESPACE="${RELEASE_K8S_NAMESPACE:-}"
export RELEASE_K8S_ROLLBACK_ENABLED="${RELEASE_K8S_ROLLBACK_ENABLED:-true}"
export RELEASE_K8S_ROLLBACK_KINDS="${RELEASE_K8S_ROLLBACK_KINDS:-deployment,statefulset,daemonset}"

echo "waiting for qdrant http://localhost:${QDRANT_HTTP_PORT}/healthz"
for _ in $(seq 1 60); do
  if curl -fsS "http://localhost:${QDRANT_HTTP_PORT}/healthz" >/dev/null 2>&1; then
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
    nohup $MVN_CMD spring-boot:run -DskipTests >"$ROOT_DIR/.logs/backend.log" 2>&1 &
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
