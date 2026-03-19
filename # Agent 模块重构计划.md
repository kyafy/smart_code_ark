# Agent 模块重构计划

## 1. Summary

目标：把当前“任务驱动式代码生成”重构为可扩展、可观测、可恢复的 Agent 编排架构；保持现有 API 兼容（/api/generate、/api/task/{id}/status、/api/task/{id}/download）。

## 2. Current State Analysis

- 任务入口在 TaskService，负责任务创建/计费/触发异步执行。
- 执行核心在 TaskExecutorService，采用固定步骤串行执行（需求分析→后端→前端→SQL→打包）。
- 模型能力集中在 ModelService（聊天、结构规划、文件生成），Prompt 与模型选择耦合在同一类。
- 前端进度页依赖轮询 status，日志为本地拼接，不是后端真实 task\_logs。
- “修改任务”当前只创建新任务，changeInstructions 未进入生成提示词。

## 3. Proposed Changes

### 3.1 后端模块拆分（保持 Controller/DTO 外观不变）

- 新增 `agent` 领域层：
  - `AgentOrchestrator`：统一调度任务生命周期（queued/running/finished/failed/cancelled）。
  - `AgentExecutionContext`：封装 project/task/spec/stack/changeInstructions/workdir。
  - `AgentStep` 接口 + 实现类：
    - RequirementAnalyzeStep
    - CodegenBackendStep
    - CodegenFrontendStep
    - SqlGenerateStep
    - PackageStep
- `TaskExecutorService` 收敛为 orchestrator 适配层，逐步下线直接 switch-case。

### 3.2 真实可观测能力

- 新增任务日志查询接口（分页/增量游标），前端 LogPanel 改为真实日志流（先轮询，后续可 SSE）。
- TaskStatusResult 扩展：
  - projectId
  - errorCode/errorMessage
  - startedAt/finishedAt
  - currentStepProgress
- 所有 step 统一写入 task\_steps（running/finished/failed + timestamps + retry\_count）。

### 3.3 可靠性与恢复

- 工作目录改为配置项（避免硬编码 /tmp）：
  - `smartark.agent.workspace-root`
- 打包、文件落盘、模型失败增加错误分类（MODEL\_ERROR/IO\_ERROR/VALIDATION\_ERROR）。
- 引入重试策略（仅幂等步骤重试），失败后可手动 `retry` 指定 step。
- 加入任务取消能力（cancel API + cooperative cancellation）。

### 3.4 生成质量

- changeInstructions 注入执行上下文，并参与后续文件生成提示词。
- 文件生成从“关键词 contains 过滤”升级为“结构化文件清单 + 标签分组”。
- Prompt 模板化：将结构规划与文件生成 prompt 抽离为可配置模板（版本化）。

### 3.5 前端体验

- task store 支持：
  - 状态 + 步骤明细 + 真实日志
  - 自动跳转结果页（finished）与失败提示（failed）
- PreviewPage 从占位页升级为“真实预览地址 + 回退到下载”。

## 4. Assumptions & Decisions

- 保持现有 REST 路由不破坏前端主流程。
- 数据库沿用现表，新增字段用增量 migration，不做破坏性变更。
- 第一阶段不引入消息队列，先在单体内实现可恢复编排。
- 兼容 mock 模式。

## 5. Verification

- 单测：AgentOrchestrator 生命周期、步骤失败回滚、重试与取消。
- 集成：从 /api/generate 到 /download 全链路。
- 前端验收：
  - 进度与日志来自后端真实数据
  - modify 指令确实影响生成结果
  - finished 自动可下载、failed 可见错误原因
- 回归：聊天流式、项目确认、历史任务列表保持可用。

