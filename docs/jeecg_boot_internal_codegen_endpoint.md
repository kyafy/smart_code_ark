# JeecgBoot Internal Codegen Endpoint (Implemented)

## Implemented

JeecgBoot side now supports a dedicated intranet endpoint:

- `POST /internal/codegen/render`
- `POST /internal/codegen/engine/render` (recommended target for pure codegen-engine mode)
- method annotated with `@IgnoreAuth` (JWT login bypass for sidecar)
- protections:
  - IP whitelist
  - HMAC signature
  - timestamp window check
  - body hash check
- delegates to Jeecg native endpoint:
  - `/online/cgform/api/codeGenerate`

For historical reasons, `/internal/codegen/render` is online-compat style.  
Pure codegen-engine integration should expose `/internal/codegen/engine/render` and accept engine params directly.

## Code Locations

- `JeecgBoot/jeecg-boot/jeecg-module-system/jeecg-system-biz/src/main/java/org/jeecg/modules/system/codegen/InternalCodegenProperties.java`
- `JeecgBoot/jeecg-boot/jeecg-module-system/jeecg-system-biz/src/main/java/org/jeecg/modules/system/controller/InternalCodegenController.java`
- `JeecgBoot/jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/application-dev.yml`
- `JeecgBoot/jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/application-docker.yml`

## Signature Contract (compatible with sidecar)

Required headers:

- `X-SmartArk-AppId`
- `X-SmartArk-Timestamp` (ms)
- `X-SmartArk-Nonce`
- `X-SmartArk-Body-SHA256`
- `X-SmartArk-Signature`
- `X-SmartArk-Sign-Version`

Signature base:

```text
METHOD\nPATH_WITH_QUERY\nTIMESTAMP\nNONCE\nBODY_SHA256
```

`METHOD` is `POST`; `PATH_WITH_QUERY` is request URI + query string.

## JeecgBoot Env

New JeecgBoot-side env names:

```bash
JEECG_CODEGEN_INTERNAL_ENABLED=true
JEECG_CODEGEN_INTERNAL_REQUIRE_SIGNATURE=true
JEECG_CODEGEN_INTERNAL_ALLOWED_IPS=127.0.0.1,::1,172.16.0.0/12,10.0.0.0/8,192.168.0.0/16
JEECG_CODEGEN_INTERNAL_APP_ID=smart_code_ark
JEECG_CODEGEN_INTERNAL_SIGN_SECRET=replace_with_shared_secret
JEECG_CODEGEN_INTERNAL_SIGN_VERSION=v1
JEECG_CODEGEN_INTERNAL_TIMESTAMP_WINDOW_SECONDS=300
JEECG_CODEGEN_INTERNAL_UPSTREAM_PATH=/online/cgform/api/codeGenerate
JEECG_CODEGEN_INTERNAL_SERVICE_USERNAME=admin
JEECG_CODEGEN_INTERNAL_SERVICE_CLIENT_TYPE=pc
```

Sidecar env (already in smart_code_ark):

```bash
JEECG_UPSTREAM_BASE_URL=http://localhost:8080/jeecg-boot
JEECG_ENGINE_DIRECT_ENABLED=true
JEECG_ENGINE_PATH=/internal/codegen/engine/render
JEECG_LEGACY_ONLINE_FALLBACK_ENABLED=true
JEECG_CODEGEN_PATH=/internal/codegen/render
JEECG_INTERNAL_APP_ID=smart_code_ark
JEECG_INTERNAL_SIGN_SECRET=replace_with_shared_secret
JEECG_INTERNAL_SIGN_VERSION=v1
```

## NoClassDefFoundError Fix (`CodeGenerateOne`)

If you see:

`NoClassDefFoundError: org/jeecgframework/codegenerate/generate/impl/CodeGenerateOne`

run build with project local Maven settings/repo:

```bash
cd JeecgBoot/jeecg-boot
mvn -s ../.mvn-settings.xml -pl jeecg-boot-base-core -am dependency:tree "-Dincludes=org.jeecgframework.boot:codegenerate"
```

Expected dependency:

`org.jeecgframework.boot:codegenerate:jar:1.5.5:compile`

Check class in local repo:

```bash
jar tf ../.m2repo/org/jeecgframework/boot/codegenerate/1.5.5/codegenerate-1.5.5.jar | grep CodeGenerateOne.class
```

Then compile:

```bash
mvn -s ../.mvn-settings.xml -pl jeecg-module-system/jeecg-system-biz -am -DskipTests compile
```

## Smoke Test

```bash
curl -X POST http://localhost:19090/api/codegen/jeecg/render \
  -H "Content-Type: application/json" \
  -d '{"taskId":"t1","projectId":"p1","workspaceDir":"/tmp/smartark/t1","jeecg":{"mode":"engine_direct","engine":{"moduleName":"demo","packageName":"com.smartark.demo","tableName":"demo_order","entityName":"DemoOrder","outputDir":"/tmp/smartark/t1"}}}'
```

Expected:

- sidecar returns 200
- JeecgBoot receives `/internal/codegen/engine/render` (recommended)
- if engine endpoint unavailable and fallback enabled, sidecar can still try `/internal/codegen/render`
