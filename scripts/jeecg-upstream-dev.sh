#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
JEECG_HOME="${JEECG_HOME:-${ROOT_DIR}/JeecgBoot/jeecg-boot}"
MVN_SETTINGS="${JEECG_MVN_SETTINGS:-${ROOT_DIR}/JeecgBoot/.mvn-settings.xml}"

if [[ ! -d "${JEECG_HOME}" ]]; then
  echo "JeecgBoot source not found: ${JEECG_HOME}" >&2
  exit 1
fi
if [[ ! -f "${MVN_SETTINGS}" ]]; then
  echo "Maven settings not found: ${MVN_SETTINGS}" >&2
  exit 1
fi

export JEECG_CODEGEN_INTERNAL_ENABLED="${JEECG_CODEGEN_INTERNAL_ENABLED:-true}"
export JEECG_CODEGEN_INTERNAL_REQUIRE_SIGNATURE="${JEECG_CODEGEN_INTERNAL_REQUIRE_SIGNATURE:-true}"
export JEECG_CODEGEN_INTERNAL_ALLOWED_IPS="${JEECG_CODEGEN_INTERNAL_ALLOWED_IPS:-127.0.0.1,::1,172.16.0.0/12,10.0.0.0/8,192.168.0.0/16}"
export JEECG_CODEGEN_INTERNAL_APP_ID="${JEECG_CODEGEN_INTERNAL_APP_ID:-smart_code_ark}"
export JEECG_CODEGEN_INTERNAL_SIGN_SECRET="${JEECG_CODEGEN_INTERNAL_SIGN_SECRET:-}"
export JEECG_CODEGEN_INTERNAL_SIGN_VERSION="${JEECG_CODEGEN_INTERNAL_SIGN_VERSION:-v1}"
export JEECG_CODEGEN_INTERNAL_TIMESTAMP_WINDOW_SECONDS="${JEECG_CODEGEN_INTERNAL_TIMESTAMP_WINDOW_SECONDS:-300}"
export JEECG_CODEGEN_INTERNAL_UPSTREAM_PATH="${JEECG_CODEGEN_INTERNAL_UPSTREAM_PATH:-/online/cgform/api/codeGenerate}"
export JEECG_CODEGEN_INTERNAL_SERVICE_USERNAME="${JEECG_CODEGEN_INTERNAL_SERVICE_USERNAME:-admin}"
export JEECG_CODEGEN_INTERNAL_SERVICE_CLIENT_TYPE="${JEECG_CODEGEN_INTERNAL_SERVICE_CLIENT_TYPE:-pc}"

cd "${JEECG_HOME}"

echo "[jeecg-upstream-dev] verifying codegenerate dependency..."
mvn -q -s "${MVN_SETTINGS}" -pl jeecg-boot-base-core -am dependency:tree "-Dincludes=org.jeecgframework.boot:codegenerate"

echo "[jeecg-upstream-dev] starting jeecg-system-start..."
mvn -s "${MVN_SETTINGS}" -pl jeecg-module-system/jeecg-system-start -am -DskipTests spring-boot:run

