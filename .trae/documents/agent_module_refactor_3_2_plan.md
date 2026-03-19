# 真实可观测能力 Spec (3.2)

## 1. 摘要 (Summary)
本计划详细展开“Agent 模块重构”中的第 3.2 节：真实可观测能力。目标是将原本前端通过状态变化“伪造”的本地日志，替换为真实的后端日志流。同时，对状态查询接口进行扩展，让前端可以准确地获取任务的执行上下文和异常详情。

## 2. 当前状态分析 (Current State Analysis)
- **后端日志收集**：`AgentOrchestrator` 已经会将日志写入 `TaskLogEntity`，但目前没有提供对应的 API 供前端读取。
- **状态字段不足**：目前的 `TaskStatusResult` 只包含 `status`, `progress`, `step`, `current_step`。这导致前端（如 `TaskProgressPage`）如果缺少 `projectId`，只能回退到项目列表。
- **前端日志伪造**：`task.ts` 中目前的日志是由前端在轮询状态时自己生成的本地假数据。

## 3. 拟议变更 (Proposed Changes)

### 3.1 扩展 `TaskStatusResult`
- **文件路径**：`services/api-gateway-java/src/main/java/com/smartark/gateway/dto/TaskStatusResult.java`
- **新增字段**：
  - `String projectId`
  - `String errorCode`
  - `String errorMessage`
  - `String startedAt`
  - `String finishedAt`
- **修改位置**：
  - 更新该 `record` 定义。
  - 在 `TaskService.getStatus` 中从 `TaskEntity` 和关联的 `TaskStepEntity` 中填充这些信息。

### 3.2 新增 `TaskLogDto` 及查询接口
- **文件路径**：`services/api-gateway-java/src/main/java/com/smartark/gateway/dto/TaskLogDto.java`
- **结构**：包含 `id`, `level`, `content`, `ts` (从 `createdAt` 转换的毫秒时间戳)。
- **文件路径**：`services/api-gateway-java/src/main/java/com/smartark/gateway/controller/TaskController.java`
- **新增端点**：`@GetMapping("/task/{taskId}/logs")`
- **文件路径**：`services/api-gateway-java/src/main/java/com/smartark/gateway/service/TaskService.java`
- **新增方法**：`getLogs(String taskId)`，通过 `TaskLogRepository.findByTaskIdOrderByCreatedAtAsc` 查询并转换为 DTO。

### 3.3 前端适配真实日志
- **文件路径**：`frontend-web/src/types/api.ts`
  - 更新 `TaskStatusResult` 接口定义，包含新增的字段。
  - 新增 `TaskLogResult` 接口，定义真实日志数据结构。
- **文件路径**：`frontend-web/src/api/endpoints.ts`
  - 在 `taskApi` 中新增 `logs: (taskId: string) => requestJson<TaskLogResult[]>({ ... })`。
- **文件路径**：`frontend-web/src/api/mock/index.ts`
  - 为 `mock` 服务添加拦截 `/api/task/{taskId}/logs`，直接返回 task 上的 logs 属性（mock 本身也是维护了一个 logs 数组）。
- **文件路径**：`frontend-web/src/stores/task.ts`
  - 在 `loadStatus` 中，移除生成本地假日志的代码。
  - 并发请求或随后请求 `taskApi.logs(id)`，使用后端返回的真实日志列表替换 `logs.value`。
- **文件路径**：`frontend-web/src/pages/TaskProgressPage.vue`
  - 更新获取 `projectId` 的逻辑，直接从 `task.status` 绑定的结果或 store 中读取（可以把 projectId 加进 store 状态里）。
  - 在轮询期间同步调用加载日志。

## 4. 假设与决策 (Assumptions & Decisions)
- **分页 vs 全量**：由于单个生成任务的日志通常在 10-50 条之间，为了简化实现，初期我们直接返回该任务的全量日志（按时间升序）。后续如需 SSE 或增量游标可以在此基础上扩展。
- **前端页面改动**：由于目前只是从 `status` 里取数据来决定跳转，所以只需扩展现有 store 字段。

## 5. 验证步骤 (Verification)
1. 发起一次新的生成任务，在前端的控制台和界面上观察。
2. 确认 `LogPanel` 显示的日志内容是如“Task started: xxx”以及各步骤开始、结束的真实内容，而非“状态更新：...”。
3. 确认任务执行完成时，页面能够因为获取到了正确的 `projectId` 而跳转至正确的项目详情页（而不是回退到项目列表）。
4. （若可能）制造一次任务失败，确认 `errorCode` 和 `errorMessage` 能够通过 `status` 接口正常返回并在前端显示。