# Agent 模块 3.3（可靠性与恢复）实施计划

## 1. Summary
本计划聚焦 [# Agent 模块重构计划.md:L36-L41](file:///e:/fuyao/project/smart_code_ark/#%20Agent%20%E6%A8%A1%E5%9D%97%E9%87%8D%E6%9E%84%E8%AE%A1%E5%88%92.md#L36-L41) 的四项能力落地：
1) workspace 目录配置化与跨平台可用；
2) 错误分类（MODEL/IO/VALIDATION）；
3) 幂等步骤重试（自动 + 手动指定 step）；
4) 任务取消（cancel API + 协作式取消）。

实现策略是“最小侵入 + 现有模型复用”：尽量复用现有 `tasks/task_steps/task_logs` 字段与状态机，仅增量补齐 API、状态流转、错误分类和编排器控制逻辑，不改动现有生成主链路的对外接口。

## 2. Current State Analysis
### 2.1 已有能力
- `workspace-root` 已存在并被编排器使用，见 [application.yml:L30-L33](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/resources/application.yml#L30-L33)、[AgentOrchestrator.java:L33-L65](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/java/com/smartark/gateway/agent/AgentOrchestrator.java#L33-L65)。
- 任务日志已写入 `task_logs`，见 [AgentOrchestrator.java:L122-L129](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/java/com/smartark/gateway/agent/AgentOrchestrator.java#L122-L129)。
- `task_steps` 已有 `error_code/error_message/retry_count` 字段，见 [V1__init.sql:L78-L95](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/resources/db/migration/V1__init.sql#L78-L95)。

### 2.2 主要缺口
- 错误分类未落地到任务/步骤：当前失败时仅设置 `task.errorMessage`，未设置 `task.errorCode`，且步骤失败未写 `failed` 与步骤错误码，见 [AgentOrchestrator.java:L90-L118](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/java/com/smartark/gateway/agent/AgentOrchestrator.java#L90-L118)。
- 无重试策略：`retry_count` 仅初始化，无自动重试与手动重试 API，见 [TaskService.java:L119-L130](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/java/com/smartark/gateway/service/TaskService.java#L119-L130)、[TaskController.java:L29-L62](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/java/com/smartark/gateway/controller/TaskController.java#L29-L62)。
- 无取消能力：无 `/cancel` 接口、无 `cancelled` 状态流转、无协作式中断检查。
- `workspace-root` 默认值仍为 Linux 风格路径，跨环境可运维性不足。

## 3. Proposed Changes

### A. 配置与错误分类基础
1. 更新 [application.yml](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/resources/application.yml)
- 新增：
  - `smartark.agent.max-retries: 2`
  - `smartark.agent.retryable-step-codes: requirement_analyze,codegen_backend,codegen_frontend,sql_generate`
- 保留：`smartark.agent.workspace-root`。
- 目的：把重试阈值与幂等步骤白名单配置化，避免硬编码。

2. 更新 [ErrorCodes.java](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/java/com/smartark/gateway/common/exception/ErrorCodes.java)
- 增加分类常量：
  - `TASK_MODEL_ERROR`
  - `TASK_IO_ERROR`
  - `TASK_VALIDATION_ERROR`
  - `TASK_CANCELLED`
- 目的：与 3.3 的错误分类目标对齐，并保持现有 `ApiResponse` 处理机制不变。

### B. 编排器可靠性增强（核心）
3. 更新 [AgentOrchestrator.java](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/java/com/smartark/gateway/agent/AgentOrchestrator.java)
- 新增方法：
  - `classifyError(Throwable e): String`：把异常映射到 MODEL/IO/VALIDATION。
  - `isRetryableStep(TaskStepEntity step)`：依据配置判断是否可重试。
  - `checkCancelled(TaskEntity task)`：每步执行前检查是否已取消。
- 调整执行循环：
  - 每步开始前刷新任务状态（防止取消信号不可见）。
  - 每步失败时写入 `task_steps.status=failed`、`error_code`、`error_message`、`retry_count+1`。
  - 对幂等步骤按 `max-retries` 自动重试（同一步内重试，不回滚已完成步骤）。
  - 超过重试次数后，写入 `tasks.status=failed`、`tasks.error_code`、`tasks.error_message`。
- 取消处理：
  - 若检测到 `task.status=cancelled`，立即停止后续步骤，写终止日志。

### C. 任务服务与 API 增量
4. 更新 [TaskService.java](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/java/com/smartark/gateway/service/TaskService.java)
- 新增 `cancelTask(String taskId)`：
  - 仅允许 owner 用户取消。
  - 仅允许 `queued/running` -> `cancelled`。
  - 写任务日志（user-cancel）。
- 新增 `retryStep(String taskId, String stepCode)`：
  - 校验任务 owner 与状态（仅 failed/cancelled 支持恢复）。
  - 定位 step 并重置该 step 到 `pending`（保留历史 `retry_count`，不清空）。
  - 把该 step 之后步骤重置为 `pending/progress=0/error_* = null`。
  - 任务置为 `running`，清空任务级 error，异步重新调度 `TaskExecutorService.executeTask(taskId)`。

5. 更新 [TaskController.java](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/java/com/smartark/gateway/controller/TaskController.java)
- 新增接口：
  - `POST /api/task/{taskId}/cancel`
  - `POST /api/task/{taskId}/retry/{stepCode}`
- 返回复用 `ApiResponse<GenerateResult>` 或新增轻量 DTO（建议复用 `GenerateResult(taskId,status)` 以减少前端改动）。

### D. 状态与前端最小联动
6. 更新 [TaskStatusResult.java](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/java/com/smartark/gateway/dto/TaskStatusResult.java)
- 状态枚举语义扩展：允许 `cancelled`。
- `errorCode/errorMessage` 已有字段继续复用，无需改结构。

7. 更新前端 API（最小必要）
- [endpoints.ts](file:///e:/fuyao/project/smart_code_ark/frontend-web/src/api/endpoints.ts)：新增 `cancel/retry` 方法。
- `task store` 与进度页仅做最小适配：
  - 当状态为 `cancelled` 时停止轮询并展示“已取消”。
  - 保持现有日志轮询链路。

### E. 数据库迁移（可选，按当前数据状态决定）
8. 若线上 `tasks` 表尚无 `instructions` 字段，则补齐迁移兼容
- 新增 [V4__task_cancel_retry_support.sql](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/resources/db/migration/)：
  - 仅在需要时补 `tasks.instructions`（若已存在则不重复）
  - 本次不新增新表，直接复用既有列。
- 说明：当前实体已有 `instructions`，但初始迁移未包含，需保证新环境可一次性启动。

## 4. Assumptions & Decisions
- 状态机采用：`pending -> running -> finished|failed|cancelled`；取消后不再自动恢复，必须通过手动 retry。
- 自动重试仅作用于幂等步骤（由配置白名单控制），`package` 不自动重试。
- 手动重试是“从指定 step 往后重放”，之前 finished 步骤保持不变。
- 取消采用协作式：在每步边界检查取消信号，不强杀正在进行的模型请求。
- 不引入消息队列，保持当前异步线程池模型。

## 5. Verification Steps
1. **编译检查**
- 后端：`mvn -DskipTests compile`
- 前端：`npm run build`

2. **功能验证：取消**
- 发起任务后调用 `/api/task/{id}/cancel`。
- 验证 `tasks.status=cancelled`，日志含 cancel 事件，前端停止轮询并显示已取消。

3. **功能验证：自动重试**
- 人为制造可重试异常（如模型服务短暂错误）。
- 验证 step 的 `retry_count` 增长，未超过阈值时任务最终可成功。

4. **功能验证：错误分类**
- 分别触发 IO 异常、模型异常、参数异常。
- 验证 `task_steps.error_code` 与 `tasks.error_code` 为对应分类码，`status` 为 `failed`。

5. **功能验证：手动重试指定 step**
- 在任务失败后调用 `/api/task/{id}/retry/{stepCode}`。
- 验证指定 step 及后续 step 被重置并重跑，完成后任务状态变为 `finished`。

6. **回归验证**
- `/api/generate`、`/api/task/{id}/status`、`/api/task/{id}/logs`、`/api/task/{id}/download` 保持兼容。
