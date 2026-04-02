# Smart Code Ark x JeecgBoot Execution Guide (v2)

## 1. Scope
This guide provides an executable rollout for these goals:

1. Migrate JeecgBoot template capability into Smart Code Ark with frontend-selectable options.
2. Integrate Jeecg rule-based generation into the current generation chain (`llm | jeecg_rule | hybrid | internal_service`).
3. Complete release pipeline: image build, image push, target deploy, deploy verify, and rollback orchestration.

## 2. Outcome Mapping

### 2.1 Template migration
Status: done in selectable mode.

- `templateId` remains the template selector.
- Frontend now exposes generation engine selection.
- Existing template flow and new Jeecg flow can coexist.

### 2.2 Jeecg generation logic migration
Status: done with frontend switch.

- `jeecg_rule`: Jeecg rule render only, fail if Jeecg render fails.
- `hybrid`: Jeecg first, fallback to LLM on failure.
- `internal_service`: force internal codegen service path (provider-based, configurable).
- `llm`: existing LLM-only path.

### 2.3 Auto build and deploy completion
Status: done for main orchestration path.

Pipeline steps now include:

- `image_build`
- `image_push`
- `deploy_target`
- `deploy_verify`
- `deploy_rollback`

## 3. Implementation Map

## 3.1 Frontend options and payload
File: `frontend-web/src/pages/StackConfirmPage.vue`

New configurable options:

- `codegenEngine`
- `deliveryLevel`
- `deployMode`
- `deployEnv`
- `autoBuildImage`
- `autoPushImage`
- `autoDeployTarget`
- `strictDelivery`

Payload is sent through `/api/generate.options`.

## 3.2 Domain contract
File: `packages/domain/src/api.ts`

`GenerateOptions` now includes all fields above.

## 3.3 Task model and DB
Files:

- `services/api-gateway-java/src/main/java/com/smartark/gateway/dto/GenerateOptions.java`
- `services/api-gateway-java/src/main/java/com/smartark/gateway/db/entity/TaskEntity.java`
- `services/api-gateway-java/src/main/resources/db/migration/V35__add_codegen_and_release_options.sql`

Added task persistence fields:

- `codegen_engine`
- `deploy_mode`
- `deploy_env`
- `strict_delivery`
- `auto_build_image`
- `auto_push_image`
- `auto_deploy_target`
- `release_status`

## 3.4 Jeecg renderer integration
Files:

- `services/api-gateway-java/src/main/java/com/smartark/gateway/service/codegen/InternalCodegenService.java`
- `services/api-gateway-java/src/main/java/com/smartark/gateway/service/codegen/JeecgCodegenProvider.java`
- `services/api-gateway-java/src/main/java/com/smartark/gateway/service/JeecgCodegenClient.java`
- `services/api-gateway-java/src/main/java/com/smartark/gateway/agent/step/RequirementAnalyzeStep.java`
- `services/api-gateway-java/src/main/java/com/smartark/gateway/agent/step/AbstractCodegenStep.java`

Behavior:

- Requirement step routes by `codegenEngine` through `InternalCodegenService`.
- `jeecg_rule` and `internal_service` run strict internal render mode.
- `hybrid` executes internal provider first and falls back to LLM on failure.
- In strict internal mode, codegen step avoids LLM overwrite and requires rendered files.

## 3.5 Release pipeline service
Files:

- `services/api-gateway-java/src/main/java/com/smartark/gateway/service/ReleaseDeployService.java`
- `services/api-gateway-java/src/main/java/com/smartark/gateway/agent/step/ImageBuildStep.java`
- `services/api-gateway-java/src/main/java/com/smartark/gateway/agent/step/ImagePushStep.java`
- `services/api-gateway-java/src/main/java/com/smartark/gateway/agent/step/DeployTargetStep.java`
- `services/api-gateway-java/src/main/java/com/smartark/gateway/agent/step/DeployVerifyStep.java`
- `services/api-gateway-java/src/main/java/com/smartark/gateway/agent/step/DeployRollbackStep.java`

Generated reports in task workspace:

- `release_image_build_report.json`
- `release_image_push_report.json`
- `release_deploy_report.json`
- `release_verify_report.json`
- `release_rollback_report.json`

## 3.6 Progress timeline and mock
Files:

- `frontend-web/src/components/StepTimeline.vue`
- `frontend-web/src/api/mock/index.ts`

- Timeline includes release steps.
- Mock `/api/generate` now composes dynamic steps from `options`.

## 4. Runtime Configuration
File: `services/api-gateway-java/src/main/resources/application.yml`

```yaml
smartark:
  codegen:
    internal:
      provider-order: local_template,jeecg
      hybrid-provider-order: local_template,jeecg
      strict-provider-order: local_template,jeecg
    local-template:
      enabled: true
      path-rewrite-enabled: true
      content-rewrite-enabled: true
      extension-rewrite-enabled: true
      overwrite-target: true
    jeecg:
      enabled: true
      base-url: http://jeecg-sidecar:19090
      render-path: /api/codegen/jeecg/render
      timeout-ms: 8000
  release:
    enabled: true
    command-execution-enabled: true
    timeout-seconds: 900
    registry-prefix: registry.example.com/team
    verify-health-url: http://your-service/health
    k8s:
      namespace: test
      rollback-enabled: true
      rollback-kinds: deployment,statefulset,daemonset
```

Notes:

- Set `command-execution-enabled=false` for dry-run style validation.
- Set `registry-prefix` to your real image registry namespace.
- Default provider order is `local_template,jeecg`: local deterministic rewrite first, sidecar fallback second.
- Sidecar supports engine-direct endpoint (recommended) via `JEECG_ENGINE_PATH` (default `/internal/codegen/engine/render`).
- Legacy compatibility endpoint remains available through `JEECG_CODEGEN_PATH` (`/internal/codegen/render` or `/online/cgform/api/codeGenerate`).
- Configure sidecar upstream env (`JEECG_UPSTREAM_BASE_URL`, `JEECG_ENGINE_DIRECT_ENABLED`, `JEECG_ENGINE_PATH`, `JEECG_LEGACY_ONLINE_FALLBACK_ENABLED`, `JEECG_CODEGEN_PATH`, `JEECG_USERNAME`, `JEECG_PASSWORD` or `JEECG_ACCESS_TOKEN`).
- Gateway now treats `jeecg.engine` / `jeecg.engineRequest` as primary inputs; `jeecg.formId/code` is legacy compatibility.
- JeecgBoot internal endpoint env uses `JEECG_CODEGEN_INTERNAL_*` (see `docs/jeecg_boot_internal_codegen_endpoint.md`).
- If startup logs show `NoClassDefFoundError: ... CodeGenerateOne`, verify `org.jeecgframework.boot:codegenerate` is resolved with `-s ../.mvn-settings.xml`.

## 5. Frontend Operator Flow

1. Open stack confirm page.
2. Select preset or custom stack.
3. Configure:
   - `Codegen Engine = jeecg_rule` or `hybrid` or `internal_service`
   - `Deploy Mode = compose` or `k8s`
   - toggle `Auto Build / Auto Push / Auto Deploy`
   - optional `Strict Delivery`
4. Click `Confirm and Generate`.
5. Observe task steps and reports.

## 6. API Example

```json
POST /api/generate
{
  "projectId": "p_xxx",
  "options": {
    "templateId": "springboot-vue3-mysql",
    "codegenEngine": "internal_service",
    "deliveryLevel": "deliverable",
    "deployMode": "k8s",
    "deployEnv": "test",
    "autoBuildImage": true,
    "autoPushImage": true,
    "autoDeployTarget": true,
    "strictDelivery": true,
    "enablePreview": true,
    "enableAutoRepair": true
  }
}
```

## 7. Validation (Executed)

Backend build:

```bash
cd services/api-gateway-java
mvn -DskipTests compile
```

Frontend build:

```bash
cd frontend-web
pnpm run build
```

Current status: both builds are verified in this rollout.

## 8. Preconditions

1. Runtime host has Docker and Docker Compose for compose mode.
2. Runtime host has `kubectl` and cluster credentials for k8s mode.
3. Registry auth (`docker login`) is ready before push.
4. Jeecg sidecar can reach JeecgBoot upstream and has valid auth config.
5. For k8s rollback automation, manifests should include workload kinds in `rollback-kinds`.

## 9. Rollback Rule

- `strictDelivery=true`: fail-fast behavior.
- `strictDelivery=false`: continue and execute rollback orchestration as configured.
- Always inspect generated release report JSON files for audit.

## 10. Recommended Next Iterations

1. Environment-specific release templates with approval gates.
2. Canary/blue-green strategy and automatic rollback policy.
3. Release asset ledger (version, image digest, batch metadata).
