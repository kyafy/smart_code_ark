# Smart Code Ark v2.0 项目预览 MVP 多 Phase Spec

## Why
当前预览能力仅返回固定链接，缺少真实状态流转、失败恢复与资源治理，无法支撑 v2.0 的“任务完成即可预览”目标。需要按 phase 分阶段落地，先确保可用，再补齐可恢复与治理能力。

## What Changes
- 新增预览领域模型与持久化：`task_preview` 表、实体与仓储，支持状态、过期时间、错误信息。
- 改造任务完成触发逻辑：仅 `generate/modify` 且 `finished` 时异步触发预览部署。
- 改造预览查询接口：`GET /api/task/{taskId}/preview` 返回真实状态对象。
- 新增重建接口：`POST /api/task/{taskId}/preview/rebuild`，仅支持 `failed/expired` 状态。
- 改造前端结果页状态机：支持 `provisioning/ready/failed/expired`、轮询、失败与过期态展示、重建动作。
- 新增资源治理能力：过期回收定时任务、并发配额限制、统一错误码反馈。
- 新增体验增强能力：`provisioning` 分阶段文案，SSE 可选并支持失败回退轮询。
- Phase 1 无沙箱约束下采用静态预览优先：仅托管前端静态产物，预览内数据由前端内置 mock 提供。

## Impact
- Affected specs: 任务编排、任务预览、结果页展示、任务恢复、资源治理、可观测与配置管理。
- Affected code:
  - `services/api-gateway-java/src/main/resources/db/migration/`
  - `services/api-gateway-java/src/main/java/com/smartark/gateway/controller/TaskController.java`
  - `services/api-gateway-java/src/main/java/com/smartark/gateway/service/TaskService.java`
  - `services/api-gateway-java/src/main/java/com/smartark/gateway/agent/AgentOrchestrator.java`
  - `services/api-gateway-java/src/main/java/com/smartark/gateway/dto/TaskPreviewResult.java`
  - `services/api-gateway-java/src/main/resources/application.yml`
  - `frontend-web/src/types/api.ts`
  - `frontend-web/src/api/endpoints.ts`
  - `frontend-web/src/pages/TaskResultPage.vue`
  - `frontend-web/src/pages/PreviewPage.vue`
  - `frontend-web/src/components/ArtifactCards.vue`

## ADDED Requirements
### Requirement: 预览状态模型与查询能力
系统 SHALL 提供任务维度的预览状态模型，至少包含 `taskId/status/previewUrl/expireAt/lastError` 字段，并通过统一查询接口返回。

#### Scenario: 成功查询预览状态
- **WHEN** 用户访问 `GET /api/task/{taskId}/preview`
- **THEN** 返回该任务最新预览状态对象而非固定占位链接

### Requirement: 任务完成自动触发预览
系统 SHALL 在任务编排完成节点仅对 `generate/modify` 类型触发预览部署，并采用异步方式执行。

#### Scenario: 目标任务触发部署
- **WHEN** `taskType in (generate, modify)` 且任务状态变为 `finished`
- **THEN** 系统异步写入/更新预览记录并进入 `provisioning`

#### Scenario: 非目标任务跳过部署
- **WHEN** 任务类型不属于 `generate/modify`
- **THEN** 系统不触发预览部署

### Requirement: 预览重建能力
系统 SHALL 提供预览重建接口，且仅允许 `failed` 与 `expired` 状态重建。

#### Scenario: 合法重建
- **WHEN** 用户对 `failed` 或 `expired` 的任务调用重建接口
- **THEN** 状态切回 `provisioning` 并进入重新部署流程

#### Scenario: 非法重建
- **WHEN** 用户对其他状态调用重建接口
- **THEN** 返回明确错误码与提示文案

### Requirement: 结果页状态机交互
系统 SHALL 在结果页基于预览状态进行渲染，并在 `provisioning` 阶段轮询，`ready` 展示预览，`failed/expired` 展示异常态和重建入口。

#### Scenario: 轮询到可预览
- **WHEN** 页面初始状态为 `provisioning`
- **THEN** 每 2-3 秒查询状态直到变为 `ready`，并加载 iframe 与新窗口入口

### Requirement: Phase 1 无沙箱静态预览策略
系统 SHALL 在 Phase 1 不启动生成项目后端实例，仅发布前端静态预览并使用前端内置 mock 数据。

#### Scenario: 无沙箱环境预览可用
- **WHEN** 任务完成并部署成功
- **THEN** `previewUrl` 可访问静态页面，页面数据不依赖预览后端运行时

## MODIFIED Requirements
### Requirement: 任务结果页预览展示
系统在任务结果页的预览能力从“单次获取链接展示”修改为“状态机驱动展示”。除展示入口外，必须支持状态轮询、异常态提示与重建流转，同时保持 ZIP 下载与修改生成功能不变。

### Requirement: 预览接口返回契约
预览查询接口返回结构从仅 `previewUrl` 扩展为 `taskId/status/previewUrl/expireAt/lastError`，前后端必须按统一字段消费。

## REMOVED Requirements
### Requirement: 固定预览链接占位实现
**Reason**: 不能表达真实部署进度、失败、过期与恢复状态，不满足 MVP 验收标准。  
**Migration**: 由 `TaskPreviewResult` 新契约与 `task_preview` 真实状态回写机制替代。
