# Smart Code Ark v2.0 项目预览 MVP 多 Phase 执行计划

## 1. Summary

* 目标：基于 `docs/v2.0_项目预览MVP_拆解清单.md`，形成可直接执行的多阶段落地方案，覆盖 v2.0.0\~v2.0.3。

* 范围：后端预览数据与部署编排、前端结果页状态体验、可恢复能力、生命周期治理与配额限制、联调验收与灰度发布。

* 原则：仅在 `generate finished` 与 `modify finished` 触发预览；部署触发异步化，不阻塞主任务完成返回；保留 ZIP 下载与修改生成链路。

* Phase 1 环境约束：无沙箱时采用“静态预览优先”，仅托管前端静态产物；预览内后端数据采用前端内置 mock。

## 2. Current State Analysis

### 2.1 后端现状

* 任务完成主链路已存在：`TaskController -> TaskService -> TaskExecutorService -> AgentOrchestrator`。

* 现有预览接口 `GET /api/task/{taskId}/preview` 已存在，但实现为固定 URL 拼接，不含真实状态流转。

* `TaskPreviewResult` 当前仅有 `previewUrl` 字段，尚未承载状态、过期时间、错误信息。

* 数据库迁移中暂无 `task_preview` 表；代码中暂无 `TaskPreviewEntity`、`TaskPreviewRepository`、`PreviewDeployService`、`POST /preview/rebuild`。

### 2.2 前端现状

* 结果页 `TaskResultPage.vue` 在进入页面时拉取一次 `previewUrl`，未实现 `provisioning/ready/failed/expired` 状态机。

* `PreviewPage.vue` 为静态 iframe 包裹；`ArtifactCards.vue` 仅展示链接入口，未承接失败/过期/重建交互。

* 任务 API 已有 `getPreview(taskId)`，但前端类型与交互未对接真实预览状态字段。

### 2.3 可直接复用的工程边界

* 后端关键文件：

  * `services/api-gateway-java/src/main/java/com/smartark/gateway/agent/AgentOrchestrator.java`

  * `services/api-gateway-java/src/main/java/com/smartark/gateway/service/TaskService.java`

  * `services/api-gateway-java/src/main/java/com/smartark/gateway/controller/TaskController.java`

  * `services/api-gateway-java/src/main/resources/db/migration/`

* 前端关键文件：

  * `frontend-web/src/pages/TaskResultPage.vue`

  * `frontend-web/src/pages/PreviewPage.vue`

  * `frontend-web/src/components/ArtifactCards.vue`

  * `frontend-web/src/api/endpoints.ts`

  * `frontend-web/src/types/api.ts`

## 3. Proposed Changes (Phased)

## Phase 0：实施准备与契约对齐（准入门槛）

### 目标

* 固化状态模型、接口契约、错误码语义，避免前后端并行时反复返工。

### 变更内容

* 约定 `TaskPreviewResult` 统一字段：`taskId/status/previewUrl/expireAt/lastError`。

* 定义状态枚举：`provisioning/ready/failed/expired`。

* 约定重建接口：`POST /api/task/{taskId}/preview/rebuild`（仅 `failed/expired`）。

* 约定可观测字段：部署开始/成功/失败/耗时日志。

* 配置契约：`preview.enabled`、`preview.autoDeployOnFinish`、`preview.defaultTtlHours`、`preview.maxConcurrentPerUser`（后续 phase 启用）。

### 文件落点

* `TaskPreviewResult` DTO、`application.yml`、前端 API 类型定义文件。

### 退出标准

* 契约文档与代码中的 DTO/type 定义一致，前后端开发可并行。

## Phase 1：v2.0.0 基础可用（数据库 + 后端核心 + 前端状态页）

### 目标

* 打通自动预览最小闭环：任务完成触发部署，结果页可展示真实状态并完成轮询到 ready。

### 后端实施

* 数据层：

  * 新增迁移脚本创建 `task_preview` 表。

  * 索引：`uk_task_id(task_id)`、`idx_user_status(user_id,status)`、`idx_expire_at(expire_at)`。

  * 新增 `TaskPreviewEntity` 与 `TaskPreviewRepository`。

* 服务层：

  * 新增 `PreviewDeployService`（部署、状态写回、失败回写）。

  * 在 `AgentOrchestrator` 任务完成分支增加触发判断：

    * 条件：`task.status == finished && task.taskType in (generate, modify)`。

    * 非目标类型直接跳过。

    * 异步触发，不阻塞主流程返回。

  * 无沙箱适配：

    * `PreviewDeployService` 在 Phase 1 仅处理静态预览包发布与 URL 回写。

    * 生成产物中的后端代码不在预览环境加载运行，仅作为下载内容保留。

* 接口层：

  * 改造 `GET /api/task/{taskId}/preview`，返回真实状态对象。

### 前端实施

* `TaskResultPage.vue` 改为状态驱动渲染：

  * `provisioning`：2-3 秒轮询查询。

  * `ready`：加载 iframe + 新窗口入口。

  * `failed`：展示错误占位。

  * `expired`：展示过期提示。

* 保持下载 ZIP 与修改生成功能不变。

* 预览运行时策略（无沙箱）：

  * 预览页面使用前端内置 mock 数据驱动，确保页面可浏览与交互演示。

  * 与真实后端联调延后到 Phase 2（可恢复）后再逐步切换。

* 同步更新 `frontend-web/src/types/api.ts` 与 `frontend-web/src/api/endpoints.ts` 的响应类型。

### 退出标准

* `generate` 和 `modify` 任务完成后，接口先返回 `provisioning`，最终进入 `ready` 并返回可访问 `previewUrl`。

* 非目标任务类型不触发预览。

* 无沙箱条件下，预览 URL 可访问静态页面，且页面数据来自前端 mock，不依赖预览后端实例。

## Phase 2：v2.0.1 可恢复（重建能力）

### 目标

* 失败或过期场景可由用户主动恢复，形成可自愈操作闭环。

### 后端实施

* 新增接口 `POST /api/task/{taskId}/preview/rebuild`。

* 状态准入：仅允许 `failed`、`expired`。

* 重建成功后状态切回 `provisioning`。

* 增加错误码映射：构建失败、启动失败、代理失败、超时失败。

### 前端实施

* 在 `failed/expired` 显示“重建预览”按钮。

* 点击后按钮 loading 且防重复提交。

* 重建成功后自动恢复轮询流程。

### 退出标准

* 失败/过期任务可通过重建进入 `ready`。

* 重建过程中状态反馈清晰，不出现误导性成功提示。

## Phase 3：v2.0.2 资源治理（回收 + 配额）

### 目标

* 限制资源占用，保证预览能力可持续运行。

### 后端实施

* 生命周期治理：

  * 增加定时任务扫描过期预览并回收实例。

  * 回收后状态置为 `expired`。

  * 清理异常实例并写入错误日志。

* 配额限制：

  * 增加“每用户并发预览数”限制（默认 2）。

  * 超限返回统一错误码与提示文案。

### 退出标准

* 到期预览可在目标窗口内回收。

* 用户超限时前后端提示一致且可理解。

## Phase 4：v2.0.3 体验增强（细粒度反馈）

### 目标

* 在长部署场景持续输出可感知进展，降低用户等待焦虑。

### 实施内容

* `provisioning` 细分阶段文案。

* 可选接入 SSE 推送，失败自动回退轮询。

### 退出标准

* 长耗时部署下持续有状态反馈。

* SSE 不可用时功能不回归。

## 4. File-Level Change Plan

### 后端

* `services/api-gateway-java/src/main/resources/db/migration/`

  * 新增预览表与索引迁移脚本（v2.0.0）。

* `services/api-gateway-java/src/main/java/com/smartark/gateway/entity/`（若当前无该目录则新增）

  * 新增 `TaskPreviewEntity`。

* `services/api-gateway-java/src/main/java/com/smartark/gateway/repository/`（若当前无该目录则新增）

  * 新增 `TaskPreviewRepository`。

* `services/api-gateway-java/src/main/java/com/smartark/gateway/service/`

  * 新增 `PreviewDeployService`、改造 `TaskService`。

* `services/api-gateway-java/src/main/java/com/smartark/gateway/agent/AgentOrchestrator.java`

  * 增加 finish 节点触发预览逻辑。

* `services/api-gateway-java/src/main/java/com/smartark/gateway/controller/TaskController.java`

  * 增加重建接口并对齐返回结构。

* `services/api-gateway-java/src/main/java/com/smartark/gateway/dto/TaskPreviewResult.java`

  * 扩展字段并承载状态信息。

* `services/api-gateway-java/src/main/resources/application.yml`

  * 增加 preview 开关、TTL、并发配额配置。

### 前端

* `frontend-web/src/types/api.ts`

  * 增加/更新预览状态与重建请求响应类型。

* `frontend-web/src/api/endpoints.ts`

  * 改造 `getPreview` 类型，新增 `rebuildPreview`。

* `frontend-web/src/pages/TaskResultPage.vue`

  * 接入状态机、轮询、失败/过期展示与重建动作。

* `frontend-web/src/pages/PreviewPage.vue`

  * 对齐 ready/异常态展示策略。

* `frontend-web/src/components/ArtifactCards.vue`

  * 对齐结果入口展示文案与状态联动。

## 5. Assumptions & Decisions

* 仅 `generate/modify` 在 `finished` 后触发自动部署，其他任务类型不触发。

* 预览触发必须异步，不延长主任务完成接口响应时间。

* Phase 1 明确不加载生成项目后端运行时：预览以静态托管为准，后端相关功能用前端 mock 演示。

* 后端真实联调与运行时隔离能力放在后续 phase 增量接入，不阻塞 MVP 首次上线。

* MVP 阶段默认保留轮询机制；SSE 作为增强能力在后续 phase 引入。

## 6. Verification Steps

### 6.1 功能验证

* 用例 1：`generate finished` 后自动触发并最终 `ready`。

* 用例 2：`modify finished` 后自动触发并最终 `ready`。

* 用例 3：部署失败进入 `failed`，执行重建后恢复至 `ready`。

* 用例 4：过期后状态为 `expired`，重建后恢复至 `ready`。

* 用例 5：非目标任务类型不触发预览部署。

* 用例 6：下载 ZIP 与修改生成功能回归通过。

* 用例 7：接口鉴权生效，跨用户不可访问他人预览。

### 6.2 可观测验证

* 检查预览部署日志完整性：开始、成功/失败、耗时。

* 检查回收任务日志：过期扫描、回收结果、异常清理。

* 统计核心指标：部署成功率、平均耗时、失败分布、重建成功率。

### 6.3 发布与回滚验证

* 发布前执行数据库迁移并确认索引生效。

* 测试环境完成端到端后再灰度。

* 灰度期开启 `preview.enabled=true` 并观察成功率与耗时。

* 异常时关闭 `preview.autoDeployOnFinish`，验证系统可快速回退到下载链路兜底。

