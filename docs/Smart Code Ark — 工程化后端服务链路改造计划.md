 Smart Code Ark — 工程化后端服务链路改造计划

     Context

     Smart Code Ark 是一个 AI 驱动的代码生成平台，当前存在以下核心问题：
     1. 启动脚本/文件缺失：生成产物缺少 mvnw、package.json 等关键文件，docker-compose 构建上下文不匹配
     2. 流程编排缺少全局规则：LLM 生成的代码质量不稳定，没有统一的工程规范约束
     3. 预览功能是占位符：后端已有完整的 6 阶段 Docker 部署管道，但 publish_gateway 阶段直接返回
     http://localhost:{port}，缺少反向代理
     4. 单测覆盖不足：代码生成链路 5 个 Step 中 CodegenBackendStep/CodegenFrontendStep/SqlGenerateStep 零测试

     目标：在不改动前端 API 契约的前提下，补全工程化链路，实现真正的 sandbox 预览，补齐单测。

     ---
     Phase 0: Flyway 迁移版本冲突修复（前置阻塞项）

     问题：存在重复版本号 — V8(×2), V9(×2), V10(×2), V11(×2), V12(×2)，Flyway 启动会报错。

     方案：将重复文件重新编号为线性序列，保持内容不变。

     ┌──────────────────────────────────────────────────────────┬─────────────────────────┐
     │                        当前文件名                        │        新版本号         │
     ├──────────────────────────────────────────────────────────┼─────────────────────────┤
     │ V1__init.sql                                             │ V1 (不变)               │
     ├──────────────────────────────────────────────────────────┼─────────────────────────┤
     │ V2__chat_session_meta.sql                                │ V2 (不变)               │
     ├──────────────────────────────────────────────────────────┼─────────────────────────┤
     │ V3__task_instructions.sql                                │ V3 (不变)               │
     ├──────────────────────────────────────────────────────────┼─────────────────────────┤
     │ V5__seed_prompt_templates.sql                            │ V4                      │
     ├──────────────────────────────────────────────────────────┼─────────────────────────┤
     │ V6__paper_outline_phase1.sql                             │ V5                      │
     ├──────────────────────────────────────────────────────────┼─────────────────────────┤
     │ V7__seed_paper_prompt_templates.sql                      │ V6                      │
     ├──────────────────────────────────────────────────────────┼─────────────────────────┤
     │ V8__task_preview_phase1.sql                              │ V7                      │
     ├──────────────────────────────────────────────────────────┼─────────────────────────┤
     │ V8__paper_outline_phase2_fields.sql                      │ V8                      │
     ├──────────────────────────────────────────────────────────┼─────────────────────────┤
     │ V9__chat_session_soft_delete.sql                         │ V9                      │
     ├──────────────────────────────────────────────────────────┼─────────────────────────┤
     │ V9__seed_paper_phase2_prompt_templates.sql               │ V10                     │
     ├──────────────────────────────────────────────────────────┼─────────────────────────┤
     │ V10__recharge_orders.sql                                 │ V11                     │
     ├──────────────────────────────────────────────────────────┼─────────────────────────┤
     │ V10__paper_phase2_output_schema_constraints.sql          │ V12                     │
     ├──────────────────────────────────────────────────────────┼─────────────────────────┤
     │ V11__strengthen_codegen_prompt_templates.sql             │ V13                     │
     ├──────────────────────────────────────────────────────────┼─────────────────────────┤
     │ V11__paper_rag_corpus.sql                                │ V14                     │
     ├──────────────────────────────────────────────────────────┼─────────────────────────┤
     │ V12__enforce_deploy_docs_and_scripts_in_codegen_plan.sql │ V15                     │
     ├──────────────────────────────────────────────────────────┼─────────────────────────┤
     │ V12__seed_rag_prompt_templates.sql                       │ V16                     │
     ├──────────────────────────────────────────────────────────┼─────────────────────────┤
     │ V13__task_preview_phase1.sql                             │ 删除（与 V7 重复内容）  │
     ├──────────────────────────────────────────────────────────┼─────────────────────────┤
     │ V14__chat_session_soft_delete.sql                        │ 删除（与 V9 重复内容）  │
     ├──────────────────────────────────────────────────────────┼─────────────────────────┤
     │ V15__recharge_orders.sql                                 │ 删除（与 V11 重复内容） │
     ├──────────────────────────────────────────────────────────┼─────────────────────────┤
     │ V16__paper_source_add_source_column.sql                  │ V17                     │
     ├──────────────────────────────────────────────────────────┼─────────────────────────┤
     │ V17__task_preview_v35_upgrade.sql                        │ V18                     │
     └──────────────────────────────────────────────────────────┴─────────────────────────┘

     注意：需要 db-reset.sh 重建数据库，或对已有环境执行 flyway repair。

     文件：services/api-gateway-java/src/main/resources/db/migration/ 全部重命名

     ---
     Phase 1: 全局工程规则体系

     1A. Prompt 全局规则注入

     目标：在所有 LLM 调用中注入统一的工程约束，从源头提升生成质量。

     实现方式：
     - 新增 Flyway 迁移 V19__seed_global_engineering_rules.sql
     - 插入 prompt_templates 记录，key = global_engineering_rules
     - System prompt 内容（全局规则）：

     ## 全局工程规范（所有生成文件必须遵守）
     1. 文件编码统一 UTF-8，换行符 LF
     2. 禁止绝对路径，所有路径为相对路径
     3. Java 后端必须包含 pom.xml + mvnw + mvnw.cmd（Maven Wrapper）
     4. 前端必须包含 package.json（含 dev/build/preview scripts）
     5. docker-compose.yml 的 build.context 必须指向实际存在的目录
     6. docker-compose.yml 必须包含 healthcheck 配置
     7. 每个 Controller 必须有参数校验 + 错误处理
     8. 禁止 TODO/FIXME/占位符代码，必须实现完整业务逻辑
     9. SQL migration 文件必须使用 Flyway 命名规范（V{n}__description.sql）
     10. 启动脚本（start.sh/deploy.sh）必须可执行

     修改文件：
     - ModelService.java (line ~329, ~413)：在 generateProjectStructure() 和 generateFileContent() 方法中，调用
     promptResolver 获取 global_engineering_rules 模板，prepend 到 system prompt 前
     - 新增私有方法 resolveGlobalRulesPrefix() 返回规则文本

     1B. 新增 ArtifactContractValidateStep（交付物契约校验）

     目标：在 sql_generate 和 package 之间插入校验步骤，确保生成产物完整可部署。

     新文件：services/api-gateway-java/src/main/java/com/smartark/gateway/agent/step/ArtifactContractValidateStep.java

     @Component
     public class ArtifactContractValidateStep implements AgentStep {
         stepCode = "artifact_contract_validate"

         execute(context):
           workspace = context.getWorkspaceDir()
           violations = new ArrayList<>()

           // 1. 检查必需文件
           mandatoryFiles = [README.md, docker-compose.yml]
           if (hasJavaBackend): mandatoryFiles += [pom.xml, mvnw, mvnw.cmd]
           if (hasFrontend): mandatoryFiles += [frontend/package.json]

           // 2. 路径安全扫描
           walkFileTree → reject files with ".." or absolute paths

           // 3. docker-compose.yml 校验
           parse YAML → check each service.build.context exists as directory

           // 4. 文件大小检查（单文件 < 1MB）

           // 5. 输出校验报告到 context
           context.setContractViolations(violations)

           // 非致命：只记录 warning，由 PackageStep 修复
           // 致命（路径穿越）：抛出异常终止

     注册步骤：修改 TaskService.java line 242-246，在 sql_generate 和 package 之间插入新步骤：

     createStep(taskId, "requirement_analyze",           "需求分析",       1);
     createStep(taskId, "codegen_backend",               "生成后端",       2);
     createStep(taskId, "codegen_frontend",              "生成前端",       3);
     createStep(taskId, "sql_generate",                  "生成 SQL",       4);
     createStep(taskId, "artifact_contract_validate",    "交付物校验",     5);  // 新增
     createStep(taskId, "package",                       "打包交付物",     6);  // order 5→6

     配置：将 artifact_contract_validate 加入可重试步骤列表 application.yml

     ---
     Phase 2: 启动脚本与文件缺失修复

     2A. 前端 Dockerfile（新增）

     新文件：frontend-web/Dockerfile

     FROM node:20-alpine AS build
     WORKDIR /app
     COPY package.json package-lock.json* ./
     RUN npm ci
     COPY . .
     RUN npm run build

     FROM nginx:1.25-alpine
     COPY --from=build /app/dist /usr/share/nginx/html
     COPY nginx.conf /etc/nginx/conf.d/default.conf
     EXPOSE 80

     新文件：frontend-web/nginx.conf — SPA fallback + proxy /api 到 api-gateway

     2B. docker-compose.yml 补全

     修改 docker-compose.yml，添加 frontend 服务：

     frontend:
       build:
         context: ./frontend-web
         dockerfile: Dockerfile
       ports:
         - "80:80"
       depends_on:
         api-gateway:
           condition: service_healthy
       environment:
         - VITE_API_BASE_URL=http://api-gateway:8080

     为 api-gateway 添加 healthcheck：

     api-gateway:
       healthcheck:
         test: ["CMD", "curl", "-f", "http://localhost:8080/health"]
         interval: 10s
         timeout: 5s
         retries: 5

     2C. dev-up.sh 修复

     修改文件：scripts/dev-up.sh
     - 检查 mvn 或 ./mvnw 可用性，优先使用 ./mvnw
     - 添加 npm install 前置检查（node_modules 存在则跳过）
     - 添加 stop-dev.sh 配套脚本用于停止所有开发进程

     ---
     Phase 3: 真实预览 — Sandbox + 反向代理

     3A. 架构设计：Spring 内置反向代理

     决策：使用 Spring WebFlux WebClient 做反向代理，而不是额外部署 nginx 容器。

     理由：
     - api-gateway 已经处理所有请求路由，不增加额外容器
     - 同源访问，无 CORS 问题
     - 前端零改动 — previewUrl 变为 /api/preview/{taskId}/ (相对路径)

     3B. PreviewProxyController（新增）

     新文件：services/api-gateway-java/src/main/java/com/smartark/gateway/controller/PreviewProxyController.java

     @RestController
     @RequestMapping("/api/preview")
     public class PreviewProxyController {

         private final TaskPreviewRepository taskPreviewRepository;
         private final RestClient restClient;

         @GetMapping("/{taskId}/**")
         public ResponseEntity<byte[]> proxy(
                 @PathVariable String taskId,
                 HttpServletRequest request) {

             // 1. 查询 TaskPreviewEntity，获取 hostPort
             TaskPreviewEntity preview = taskPreviewRepository.findByTaskId(taskId)
                 .orElseThrow(() -> new BusinessException(NOT_FOUND, "预览不存在"));

             if (!"ready".equals(preview.getStatus())) {
                 throw new BusinessException(CONFLICT, "预览未就绪");
             }

             // 2. 提取代理路径：/api/preview/{taskId}/xxx → /xxx
             String path = extractProxyPath(request, taskId);

             // 3. 转发到容器：http://localhost:{hostPort}/{path}
             String targetUrl = "http://localhost:" + preview.getHostPort() + "/" + path;

             // 4. 代理请求，透传 headers
             return restClient.get()
                 .uri(targetUrl)
                 .retrieve()
                 .toEntity(byte[].class);
         }
     }

     3C. 数据模型变更

     新增 Flyway 迁移：V20__preview_add_host_port.sql

     ALTER TABLE task_preview ADD COLUMN host_port INT DEFAULT NULL;

     修改 Entity：TaskPreviewEntity.java 添加 hostPort 字段

     3D. PreviewDeployService 修改

     修改文件：PreviewDeployService.java line 148-206

     // Phase 2: start_runtime — 保存 hostPort
     preview.setHostPort(hostPort);  // 新增
     preview.setRuntimeId(containerId);
     taskPreviewRepository.save(preview);

     // Phase 6: publish_gateway — 改用代理 URL
     String previewUrl = "/api/preview/" + taskId + "/";  // 原: "http://localhost:" + hostPort

     3E. 前端兼容性分析

     无需改动前端。原因：
     - TaskResultPage.vue 的 isNoSandboxPreviewUrl() 检查的是 /preview/{taskId} 模式（不带 /api 前缀）
     - 新 URL /api/preview/{taskId}/ 不匹配该模式，因此不会触发 mock fallback
     - iframe 加载相对路径 /api/preview/{taskId}/ 自动走同源代理
     - SSE 端点、下载端点等完全不变

     3F. 静态回退保持不变

     当 Docker 不可用时，deployStaticFallback() 仍返回 /preview/{taskId} 触发前端 mock 展示，保证降级体验。

     3G. 网络隔离

     在 ContainerRuntimeService.createAndStartContainer() 中：
     - 创建专用 Docker network smartark-preview-net
     - Preview 容器接入此网络
     - 容器间互相隔离，只通过 host port mapping 与宿主通信

     ---
     Phase 4: 补齐单测

     4A. CodegenBackendStepTest（新增）

     新文件：services/api-gateway-java/src/test/java/com/smartark/gateway/agent/step/CodegenBackendStepTest.java

     测试场景：
     1. execute_generatesBackendFilesOnly — filePlan 含前后端文件，只生成 backend group
     2. execute_skipsEmptyFilePlan — filePlan 为空，不抛异常
     3. execute_convertLegacyFileList — 仅有 fileList 无 filePlan，自动转换
     4. execute_rejectsPathTraversal — 含 ../etc/passwd 的路径被过滤

     依赖 mock：ModelService.generateFileContent() → 返回 stub 文件内容

     4B. CodegenFrontendStepTest（新增）

     新文件：services/api-gateway-java/src/test/java/com/smartark/gateway/agent/step/CodegenFrontendStepTest.java

     测试场景：
     1. execute_generatesFrontendFilesOnly — 只处理 frontend group
     2. execute_handlesVueAndReactPaths — frontend/src/App.vue 和 frontend/src/App.tsx 都归为 frontend

     4C. SqlGenerateStepTest（新增）

     新文件：services/api-gateway-java/src/test/java/com/smartark/gateway/agent/step/SqlGenerateStepTest.java

     测试场景：
     1. execute_processesAllThreeGroups — database + infra + docs 三组都被处理
     2. execute_dockerComposeClassifiedAsInfra — docker-compose.yml 归入 infra
     3. execute_readmeMdClassifiedAsDocs — README.md 归入 docs

     4D. ArtifactContractValidateStepTest（新增）

     新文件：services/api-gateway-java/src/test/java/com/smartark/gateway/agent/step/ArtifactContractValidateStepTest.ja
     va

     测试场景：
     1. execute_passesWithCompleteArtifact — 所有必需文件存在 → 无 violation
     2. execute_detectsMissingReadme — 缺少 README.md → 记录 warning
     3. execute_detectsInvalidComposeContext — compose context 指向不存在目录 → violation
     4. execute_throwsOnPathTraversal — 检测到 ../ 路径 → 抛异常终止任务
     5. execute_detectsOversizedFile — 文件 > 1MB → 记录 warning

     4E. PromptRendererTest（新增）

     新文件：services/api-gateway-java/src/test/java/com/smartark/gateway/prompt/PromptRendererTest.java

     测试场景：
     1. render_replacesAllVariables — {{prd}} → 实际值
     2. render_handlesNullValues — 变量为 null → 替换为空字符串
     3. render_preservesUnknownPlaceholders — {{unknown}} 保持原样
     4. render_handlesEmptyTemplate — 空模板 → 空字符串

     4F. PreviewProxyControllerTest（新增）

     新文件：services/api-gateway-java/src/test/java/com/smartark/gateway/controller/PreviewProxyControllerTest.java

     测试场景：
     1. proxy_forwardsToContainer — 正常代理转发
     2. proxy_returns404WhenPreviewNotFound — taskId 不存在
     3. proxy_returns409WhenNotReady — status != "ready"
     4. proxy_stripsPrefix — /api/preview/abc/index.html → /index.html

     ---
     Phase 5: API 输入输出规范（完整）

     核心接口规范

     所有接口统一响应格式：
     { "code": 0, "message": "ok", "data": { ... } }

     代码生成链路

     ┌─────────────────────────────────────┬────────┬──────────────────────────────┬────────────────────┬───────────────
     ┐
     │                接口                 │ Method │            请求体            │     响应 data      │     说明
     │
     ├─────────────────────────────────────┼────────┼──────────────────────────────┼────────────────────┼───────────────
     ┤
     │ /api/generate                       │ POST   │ { projectId, instructions? } │ { taskId, status } │ 创建生成任务
     │
     ├─────────────────────────────────────┼────────┼──────────────────────────────┼────────────────────┼───────────────
     ┤
     │ /api/task/{taskId}/status           │ GET    │ -                            │ TaskStatusResult   │ 查询任务状态
     │
     ├─────────────────────────────────────┼────────┼──────────────────────────────┼────────────────────┼───────────────
     ┤
     │ /api/task/{taskId}/cancel           │ POST   │ -                            │ { taskId, status } │ 取消任务
     │
     ├─────────────────────────────────────┼────────┼──────────────────────────────┼────────────────────┼───────────────
     ┤
     │ /api/task/{taskId}/modify           │ POST   │ { changeInstructions }       │ { taskId, status } │ 修改任务
     │
     ├─────────────────────────────────────┼────────┼──────────────────────────────┼────────────────────┼───────────────
     ┤
     │ /api/task/{taskId}/retry/{stepCode} │ POST   │ -                            │ { taskId, status } │ 重试指定步骤
     │
     ├─────────────────────────────────────┼────────┼──────────────────────────────┼────────────────────┼───────────────
     ┤
     │ /api/task/{taskId}/download         │ GET    │ -                            │ application/zip    │ 下载 ZIP 产物
     │
     ├─────────────────────────────────────┼────────┼──────────────────────────────┼────────────────────┼───────────────
     ┤
     │ /api/task/{taskId}/logs             │ GET    │ -                            │ List<TaskLogDto>   │ 查询任务日志
     │
     └─────────────────────────────────────┴────────┴──────────────────────────────┴────────────────────┴───────────────
     ┘

     TaskStatusResult:
     {
       "status": "queued|running|finished|failed|cancelled",
       "progress": 0-100,
       "currentStep": "requirement_analyze|codegen_backend|...|package",
       "currentStepName": "需求分析|...",
       "projectId": "xxx",
       "errorCode": "3001|null",
       "errorMessage": "...|null",
       "startedAt": "2026-03-23T10:00:00",
       "finishedAt": "2026-03-23T10:05:00|null"
     }

     步骤编排（6 步，含新增）:
     1. requirement_analyze  → 需求分析（生成文件计划）
     2. codegen_backend      → 生成后端代码
     3. codegen_frontend     → 生成前端代码
     4. sql_generate         → 生成 SQL/基础设施/文档
     5. artifact_contract_validate → 交付物契约校验（新增）
     6. package              → 打包交付物 + 修复缺失文件

     预览链路

     ┌────────────────────────────────────┬──────────┬───────────┬───────────────────────┬───────────────┐
     │                接口                │  Method  │  请求体   │       响应 data       │     说明      │
     ├────────────────────────────────────┼──────────┼───────────┼───────────────────────┼───────────────┤
     │ /api/task/{taskId}/preview         │ GET      │ -         │ TaskPreviewResult     │ 查询预览状态  │
     ├────────────────────────────────────┼──────────┼───────────┼───────────────────────┼───────────────┤
     │ /api/task/{taskId}/preview/rebuild │ POST     │ -         │ TaskPreviewResult     │ 重建预览      │
     ├────────────────────────────────────┼──────────┼───────────┼───────────────────────┼───────────────┤
     │ /api/task/{taskId}/preview/events  │ GET(SSE) │ -         │ event: preview-status │ 实时状态推送  │
     ├────────────────────────────────────┼──────────┼───────────┼───────────────────────┼───────────────┤
     │ /api/task/{taskId}/preview/logs    │ GET      │ ?tail=200 │ PreviewLogsResult     │ 查询构建日志  │
     ├────────────────────────────────────┼──────────┼───────────┼───────────────────────┼───────────────┤
     │ /api/preview/{taskId}/**           │ GET      │ -         │ 代理响应              │ 新增 反向代理 │
     └────────────────────────────────────┴──────────┴───────────┴───────────────────────┴───────────────┘

     TaskPreviewResult:
     {
       "taskId": "xxx",
       "status": "provisioning|ready|failed|expired",
       "phase": "prepare_artifact|start_runtime|install_deps|boot_service|health_check|publish_gateway|null",
       "previewUrl": "/api/preview/{taskId}/",
       "expireAt": "2026-03-24T10:00:00",
       "lastError": "...|null",
       "lastErrorCode": 3101,
       "buildLogUrl": "file:///tmp/smartark/preview-logs/xxx-install.log"
     }

     预览部署 6 阶段:
     prepare_artifact → start_runtime → install_deps → boot_service → health_check → publish_gateway

     错误码:

     ┌──────┬───────────────────────────────┬──────────────────────────────┐
     │ Code │             常量              │             说明             │
     ├──────┼───────────────────────────────┼──────────────────────────────┤
     │ 3101 │ PREVIEW_BUILD_FAILED          │ 构建失败                     │
     ├──────┼───────────────────────────────┼──────────────────────────────┤
     │ 3102 │ PREVIEW_START_FAILED          │ 启动失败                     │
     ├──────┼───────────────────────────────┼──────────────────────────────┤
     │ 3103 │ PREVIEW_PROXY_FAILED          │ 代理失败                     │
     ├──────┼───────────────────────────────┼──────────────────────────────┤
     │ 3104 │ PREVIEW_TIMEOUT               │ 健康检查超时                 │
     ├──────┼───────────────────────────────┼──────────────────────────────┤
     │ 3105 │ PREVIEW_REBUILD_STATE_INVALID │ 非 failed/expired 不允许重建 │
     ├──────┼───────────────────────────────┼──────────────────────────────┤
     │ 3106 │ PREVIEW_CONCURRENCY_LIMIT     │ 并发预览数超限               │
     └──────┴───────────────────────────────┴──────────────────────────────┘

     ---
     实施顺序

     ┌──────┬─────────┬───────────────────────────────┬──────────────┬────────────────────────────────────────────────┐
     │ 序号 │  Phase  │             描述              │     风险     │                    预估改动                    │
     ├──────┼─────────┼───────────────────────────────┼──────────────┼────────────────────────────────────────────────┤
     │ 1    │ Phase 0 │ Flyway 迁移重编号             │ 中（需重建   │ 重命名 ~20 文件                                │
     │      │         │                               │ DB）         │                                                │
     ├──────┼─────────┼───────────────────────────────┼──────────────┼────────────────────────────────────────────────┤
     │ 2    │ Phase   │ 全局 Prompt 规则              │ 低           │ ModelService + 1 migration                     │
     │      │ 1A      │                               │              │                                                │
     ├──────┼─────────┼───────────────────────────────┼──────────────┼────────────────────────────────────────────────┤
     │ 3    │ Phase   │ ArtifactContractValidateStep  │ 低           │ 新增 1 Step + TaskService 改动                 │
     │      │ 1B      │                               │              │                                                │
     ├──────┼─────────┼───────────────────────────────┼──────────────┼────────────────────────────────────────────────┤
     │ 4    │ Phase   │ PromptRenderer + Contract     │ 低           │ 新增 2 测试文件                                │
     │      │ 4D-E    │ 测试                          │              │                                                │
     ├──────┼─────────┼───────────────────────────────┼──────────────┼────────────────────────────────────────────────┤
     │ 5    │ Phase   │ Codegen Step 三件套测试       │ 低           │ 新增 3 测试文件                                │
     │      │ 4A-C    │                               │              │                                                │
     ├──────┼─────────┼───────────────────────────────┼──────────────┼────────────────────────────────────────────────┤
     │ 6    │ Phase 2 │ 启动脚本 + Dockerfile 修复    │ 低           │ 新增 frontend Dockerfile, nginx.conf; 修改     │
     │      │         │                               │              │ docker-compose, dev-up.sh                      │
     ├──────┼─────────┼───────────────────────────────┼──────────────┼────────────────────────────────────────────────┤
     │ 7    │ Phase 3 │ 预览反向代理                  │ 中           │ 新增 ProxyController; 修改                     │
     │      │         │                               │              │ PreviewDeployService, Entity, migration        │
     ├──────┼─────────┼───────────────────────────────┼──────────────┼────────────────────────────────────────────────┤
     │ 8    │ Phase   │ PreviewProxyController 测试   │ 低           │ 新增 1 测试文件                                │
     │      │ 4F      │                               │              │                                                │
     └──────┴─────────┴───────────────────────────────┴──────────────┴────────────────────────────────────────────────┘

     ---
     关键文件清单

     需修改的现有文件：
     - services/api-gateway-java/src/main/java/com/smartark/gateway/service/ModelService.java — 注入全局规则
     - services/api-gateway-java/src/main/java/com/smartark/gateway/service/TaskService.java (line 242-246) — 插入新步骤
     - services/api-gateway-java/src/main/java/com/smartark/gateway/service/PreviewDeployService.java (line 148, 206) —
     保存 hostPort, 改 URL
     - services/api-gateway-java/src/main/java/com/smartark/gateway/db/entity/TaskPreviewEntity.java — 添加 hostPort
     字段
     - services/api-gateway-java/src/main/resources/db/migration/ — 全部重命名 + 新增 2 个迁移
     - docker-compose.yml — 添加 frontend 服务 + healthcheck
     - scripts/dev-up.sh — 修复 mvnw 检测

     需新增的文件：
     - ArtifactContractValidateStep.java — 交付物校验步骤
     - PreviewProxyController.java — 反向代理控制器
     - frontend-web/Dockerfile — 前端容器化
     - frontend-web/nginx.conf — SPA + API 反向代理
     - scripts/stop-dev.sh — 停止开发服务脚本
     - V19__seed_global_engineering_rules.sql — 全局规则模板
     - V20__preview_add_host_port.sql — preview 表加列
     - 6 个测试文件（Phase 4A-F）

     ---
     验证方案

     单元测试验证

     cd services/api-gateway-java
     mvn test -pl . -Dtest="CodegenBackendStepTest,CodegenFrontendStepTest,SqlGenerateStepTest,ArtifactContractValidateS
     tepTest,PromptRendererTest,PreviewProxyControllerTest"

     集成验证

     1. scripts/db-reset.sh — 重建数据库，验证 Flyway 迁移顺序正确
     2. docker compose up --build — 验证前后端容器正常启动
     3. 创建项目 → 触发代码生成 → 观察 6 步骤依次完成
     4. 任务完成后 → 预览自动部署 → SSE 推送 phase 变化
     5. 访问 /api/preview/{taskId}/ → 验证反向代理返回容器内容
     6. 24h 后（或手动调整 TTL）→ 验证容器自动回收