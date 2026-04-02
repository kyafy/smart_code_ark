# Jeecg Sidecar Docker Compose Quickstart

## Purpose

`jeecg-sidecar` calls JeecgBoot upstream API with two modes:

- engine-direct (recommended): `POST /internal/codegen/engine/render`
- online-compat fallback (optional): `POST /internal/codegen/render` or `/online/cgform/api/codeGenerate`
- sidecar endpoint for SmartArk: `POST /api/codegen/jeecg/render`

## Prerequisites

You need a reachable JeecgBoot service with codegen endpoint enabled.

Recommended env:

```bash
JEECG_UPSTREAM_BASE_URL=http://localhost:8080/jeecg-boot
JEECG_LOGIN_PATH=/sys/login
JEECG_ENGINE_DIRECT_ENABLED=true
JEECG_ENGINE_PATH=/internal/codegen/engine/render
JEECG_LEGACY_ONLINE_FALLBACK_ENABLED=true
JEECG_CODEGEN_PATH=/internal/codegen/render
JEECG_INTERNAL_APP_ID=smart_code_ark
JEECG_INTERNAL_SIGN_SECRET=replace_with_shared_secret
JEECG_INTERNAL_SIGN_VERSION=v1
JEECG_USERNAME=admin
JEECG_PASSWORD=admin123
```

JeecgBoot internal endpoint env (on JeecgBoot side):

```bash
JEECG_CODEGEN_INTERNAL_ENABLED=true
JEECG_CODEGEN_INTERNAL_REQUIRE_SIGNATURE=true
JEECG_CODEGEN_INTERNAL_ALLOWED_IPS=127.0.0.1,::1,172.16.0.0/12,10.0.0.0/8,192.168.0.0/16
JEECG_CODEGEN_INTERNAL_APP_ID=smart_code_ark
JEECG_CODEGEN_INTERNAL_SIGN_SECRET=replace_with_shared_secret
JEECG_CODEGEN_INTERNAL_SIGN_VERSION=v1
JEECG_CODEGEN_INTERNAL_TIMESTAMP_WINDOW_SECONDS=300
JEECG_CODEGEN_INTERNAL_UPSTREAM_PATH=/online/cgform/api/codeGenerate  # legacy fallback
JEECG_CODEGEN_INTERNAL_SERVICE_USERNAME=admin
JEECG_CODEGEN_INTERNAL_SERVICE_CLIENT_TYPE=pc
```

If your JeecgBoot only exposes legacy online endpoint:

```bash
JEECG_ENGINE_DIRECT_ENABLED=false
JEECG_CODEGEN_PATH=/online/cgform/api/codeGenerate
```

Release pipeline env (api-gateway reads these directly):

```bash
RELEASE_ENABLED=true
RELEASE_COMMAND_EXECUTION_ENABLED=true
RELEASE_TIMEOUT_SECONDS=900
RELEASE_REGISTRY_PREFIX=registry.example.com/team
RELEASE_VERIFY_HEALTH_URL=
RELEASE_K8S_NAMESPACE=default
RELEASE_K8S_ROLLBACK_ENABLED=true
RELEASE_K8S_ROLLBACK_KINDS=deployment,statefulset,daemonset
```

Internal codegen service provider order (optional):

```bash
CODEGEN_INTERNAL_PROVIDER_ORDER=local_template,jeecg
CODEGEN_INTERNAL_HYBRID_PROVIDER_ORDER=local_template,jeecg
CODEGEN_INTERNAL_STRICT_PROVIDER_ORDER=local_template,jeecg
```

For `codegen_engine=jeecg_rule`, api-gateway now forces Jeecg provider path (sidecar must be called).

If you already have token-based auth, you can use:

```bash
JEECG_ACCESS_TOKEN=xxxx
```

## Start

```bash
docker compose up -d jeecg-sidecar api-gateway
```

or:

```bash
docker compose up -d
```

## Health Check

```bash
curl http://localhost:19090/health
```

Response includes upstream info:

```json
{
  "status":"ok",
  "detail":"ready",
  "upstreamBaseUrl":"http://...",
  "codegenPath":"/internal/codegen/render",
  "engineDirectEnabled":true,
  "enginePath":"/internal/codegen/engine/render",
  "legacyOnlineFallbackEnabled":true
}
```

## Render API Smoke

Recommended (engine-direct):

```bash
curl -X POST http://localhost:19090/api/codegen/jeecg/render \
  -H "Content-Type: application/json" \
  -d '{
    "taskId":"t1",
    "projectId":"p1",
    "workspaceDir":"/tmp/smartark/t1",
    "jeecg":{
      "mode":"engine_direct",
      "engine":{
        "moduleName":"demo",
        "packageName":"com.smartark.demo",
        "tableName":"demo_order",
        "entityName":"DemoOrder",
        "outputDir":"/tmp/smartark/t1"
      }
    },
    "stack":{"backend":"springboot","frontend":"vue3","db":"mysql"}
  }'
```

Legacy fallback (only when upstream still requires online identity):

```bash
curl -X POST http://localhost:19090/api/codegen/jeecg/render \
  -H "Content-Type: application/json" \
  -d '{
    "taskId":"t1",
    "projectId":"p1",
    "workspaceDir":"/tmp/smartark/t1",
    "jeecg":{
      "code":"05a3a30dada7411c9109306aa4117068"
    }
  }'
```

Notes:

- `jeecg.engine` / `jeecg.engineRequest` is now the primary request shape.
- `jeecg.code` / `jeecg.formId` is legacy online compatibility only.
- when `JEECG_INTERNAL_SIGN_SECRET` is set, sidecar will send:
  - `X-SmartArk-AppId`
  - `X-SmartArk-Timestamp`
  - `X-SmartArk-Nonce`
  - `X-SmartArk-Body-SHA256`
  - `X-SmartArk-Signature`
  - `X-SmartArk-Sign-Version`
- sidecar request order: `explicit request` -> `engine-direct` -> `online-compat fallback`.
- if Jeecg returns success without explicit file list, sidecar falls back to template file list for SmartArk pipeline continuity.

## Local Dev Scripts

- `scripts/dev-up.sh` starts `jeecg-sidecar` together with mysql/redis/qdrant.
- `scripts/dev-down.sh` stops `jeecg-sidecar` too.
- `scripts/jeecg-upstream-dev.sh` starts local JeecgBoot (`jeecg-system-start`) with internal codegen env and local Maven settings.
