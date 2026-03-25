# 工程化后端服务链路设计（Codegen + Preview）v4.0

## 1. 背景与现状问题
当前下载产物的主要稳定性问题：
1. 一键启动相关文件缺失（如 `frontend/package.json`、`backend/mvnw`）导致不可启动。
2. `docker-compose.yml` 的 `build.context` 指向不存在目录，容器无法构建。
3. 预览链路仍存在占位返回，缺少“沙箱运行 + 反向代理”的真实交付闭环。
4. 链路单测覆盖不足，缺少“交付物完整性”与“构建上下文修复”校验。

## 2. 设计目标
1. **稳定交付优先**：生成结果默认可运行、可构建、可预览。
2. **前端交互稳定**：保留现有接口路径和核心字段，新增字段全部可选。
3. **链路可观测**：每个节点有 phase/status，失败可追溯到错误码和日志。
4. **可演进**：支持从本地端口直出平滑升级到反向代理域名。

## 3. 总体架构

### 3.1 逻辑分层
1. **API Gateway 层**（现有）：任务创建、查询、预览触发、日志读取。
2. **Agent Orchestrator 层**（现有）：`requirement_analyze -> codegen_backend -> codegen_frontend -> sql_generate -> package`。
3. **Delivery Guard（内置在 package 步骤）**（新增）：
   - 补齐关键交付文件。
   - 修复 `docker-compose` 构建上下文。
   - 通过后再打包。
4. **Preview Runtime 层**（现有能力 + 建议增强）：
   - 容器沙箱拉起、依赖安装、启动、健康检查。
5. **Preview Gateway 层**（建议增强）：
   - 反向代理路由发布（域名/路径），统一入口。

### 3.2 核心状态机
`queued -> running -> finished/failed`（任务）

预览子状态：
`provisioning(prepare_artifact/start_runtime/install_deps/boot_service/health_check/publish_gateway) -> ready | failed | expired`

## 4. 编排节点与全局 Rule

### 4.1 节点合理性结论
现有节点整体合理，但缺失“产物级验收”。建议不新增前端可见节点，而在 `package` 节点内加入 Delivery Guard，避免前端流程变更。

### 4.2 全局 Rule（必须执行）
1. 必备文件规则：
   - `README.md`
   - `docs/deploy.md`
   - `scripts/start.sh`
   - `scripts/deploy.sh`
   - `docker-compose.yml`
   - Java/Spring 后端必须有 `backend/pom.xml`、`backend/mvnw`、`backend/mvnw.cmd`
   - 有前端时必须有 `*/package.json`
2. `docker-compose` 规则：
   - 每个 `build.context` 必须是存在的相对路径。
   - 修复优先级：按服务名识别（backend/api/server -> 后端目录；frontend/web/client -> 前端目录）。
3. 兜底规则：
   - 若缺失脚本/文档/compose，自动生成最小可运行版本。

## 5. Prompt 体系建议

### 5.1 结构规划 Prompt（project_structure_plan）
强制输出 JSON 数组（仅路径），并显式声明：
1. 必备部署文件清单。
2. Java 后端必须包含 `mvnw`。
3. 前端必须包含 `package.json`。
4. `docker-compose` 构建上下文必须可解析到项目内目录。

### 5.2 文件生成 Prompt（file_content_generate）
对关键文件追加约束：
1. `docker-compose.yml`：`build.context` 必须指向已生成目录。
2. `package.json`：必须包含 `dev/build/preview` scripts。
3. `mvnw/mvnw.cmd`：必须输出可执行 wrapper。
4. `scripts/start.sh`：必须一键启动（含 compose up/build）。

## 6. 预览架构：Sandbox + 反向代理

### 6.1 目标形态
1. **Sandbox Runtime**：每个 task 独立容器，挂载产物目录，只暴露内部端口。
2. **Preview Gateway**：统一入口域名（如 `preview.example.com`），按 `taskId` 路由到对应 sandbox。
3. **前端 iframe 安全**：`sandbox` 属性 + 严格 CSP + 短时 ticket。

### 6.2 推荐落地路径
1. Phase 1（当前兼容）：`http://localhost:{port}` 直连。
2. Phase 2（目标）：`https://preview.example.com/t/{taskId}?ticket=...` 代理访问。

### 6.3 关键安全控制
1. 沙箱资源限制：CPU/Memory/TTL/并发上限。
2. 网络隔离：默认禁止出网（按白名单放行依赖源）。
3. 访问鉴权：预览 ticket（短期有效、一次性可选）。
4. 自动回收：TTL 到期后 stop + rm 容器。

## 7. 后端接口输入输出规范（兼容 + 增量）

### 7.1 保持不变接口
1. `POST /api/generate`
   - 请求：`{ projectId: string, instructions: string }`
   - 响应：`{ taskId: string, status: "queued" | "running" | "finished" | "failed" }`
2. `GET /api/task/{taskId}/status`
   - 响应包含：`status/progress/currentStep/errorCode/errorMessage`
3. `GET /api/task/{taskId}/preview`
   - 兼容字段：`taskId/status/phase/previewUrl/expireAt/lastError/lastErrorCode/buildLogUrl`
4. `POST /api/task/{taskId}/preview/rebuild`
5. `GET /api/task/{taskId}/preview/logs?tail=200`

### 7.2 新增建议接口（不破坏现有）
1. `POST /api/task/{taskId}/delivery/validate`（可选手动重检）
   - 请求：`{ autoFix?: boolean }`
   - 响应：
```json
{
  "taskId": "...",
  "valid": true,
  "fixed": ["backend/mvnw", "frontend/package.json"],
  "warnings": []
}
```
2. `POST /api/task/{taskId}/preview/ticket`（代理模式下）
   - 请求：`{ ttlSeconds?: number }`
   - 响应：
```json
{
  "taskId": "...",
  "previewUrl": "https://preview.example.com/t/{taskId}",
  "ticket": "jwt-or-signed-token",
  "expireAt": "2026-03-23T12:00:00Z"
}
```
3. `POST /internal/preview/{taskId}/publish`（内部）
   - 请求：
```json
{
  "runtimeId": "container-id",
  "targetHost": "sandbox-123.internal",
  "targetPort": 5173
}
```
   - 响应：`{ routeId, publicUrl }`

### 7.3 错误码规范（预览相关）
1. `3101 PREVIEW_BUILD_FAILED`
2. `3102 PREVIEW_START_FAILED`
3. `3103 PREVIEW_PROXY_FAILED`
4. `3104 PREVIEW_TIMEOUT`
5. `3105 PREVIEW_REBUILD_STATE_INVALID`
6. `3106 PREVIEW_CONCURRENCY_LIMIT`

## 8. 测试策略（补齐）

### 8.1 已补齐（本次）
1. `RequirementAnalyzeStepTest`：校验 `backend/mvnw`、`frontend/package.json` 等必备文件路径被强制纳入计划。
2. `PackageStepTest`：
   - 缺失文件自动补齐。
   - `docker-compose` 错误 `context` 自动修复。
   - 打包产物 zip 包含关键文件。

### 8.2 建议补充
1. `PreviewDeployService` 代理模式单测（route publish 成功/失败）。
2. `TaskController` 新增接口契约测试（delivery validate、ticket）。
3. E2E：从生成 -> 修复 -> compose build -> preview ready 全链路冒烟。

## 9. 与前端兼容策略
1. 现有接口路径不改。
2. `GET /preview` 原字段不删，新字段仅追加。
3. 前端页面继续按 `status/phase` 渲染，若 `previewUrl` 指向代理域名即可无缝切换。

## 10. 版本化发布建议
1. `v4.0.1`：启用 Delivery Guard（当前已落地方向）。
2. `v4.1.0`：接入 Preview Gateway + ticket。
3. `v4.2.0`：引入沙箱网络白名单与更严格资源隔离。
