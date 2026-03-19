# Agent 模块重构 Spec

## Why
当前系统中的任务执行逻辑（`TaskExecutorService`）将任务状态流转、模型调用、文件生成和打包等逻辑严重耦合在一个串行的 `switch-case` 中，导致代码难以维护、无法支持重试或错误恢复，且 `modify` 接口传入的 `changeInstructions`（修改指令）目前被直接丢弃，未能真正参与到重新生成的流程中。为了提高模块的内聚性、可扩展性以及实际生成质量，需要重新构建 Agent 执行模块。

## What Changes
- 引入策略与上下文模式，新建 `AgentOrchestrator` 统一调度任务生命周期，提取 `AgentExecutionContext` 贯穿各步骤共享状态。
- 新增 `AgentStep` 接口，并拆分出 `RequirementAnalyzeStep`, `CodegenBackendStep`, `CodegenFrontendStep`, `SqlGenerateStep`, `PackageStep` 5个具体实现类。
- `TaskExecutorService` 简化为异步执行入口，内部委托给 `AgentOrchestrator`。
- **BREAKING (Database)**: `tasks` 表新增 `instructions` 字段，以持久化保存用户传入的修改指令（对应 `modify` 接口的 `changeInstructions`），并提供 V3 迁移脚本。
- `AgentExecutionContext` 在初始化时从 `ProjectSpecEntity` 和 `TaskEntity.instructions` 中加载数据，以保证后续生成过程能够将修改指令加入 Prompt。
- 将原本硬编码的临时工作目录 `/tmp/smartark/` 修改为通过 `application.yml` 配置的 `smartark.agent.workspace-root`，以支持跨平台兼容。

## Impact
- Affected specs: 任务执行流程、修改任务流程 (modify API)。
- Affected code:
  - `TaskExecutorService` (逻辑剥离)
  - `TaskService` (持久化 instructions)
  - `TaskEntity` (新增字段)
  - 新增 `com.smartark.gateway.agent` 包及下属类。
  - `application.yml` (新增配置项)
  - 新增 Flyway 迁移脚本 `V3__task_instructions.sql`。

## ADDED Requirements
### Requirement: 任务编排与状态上下文管理
The system SHALL provide `AgentOrchestrator` 来管理任务的生命周期，提供统一的状态流转和日志记录；并提供 `AgentExecutionContext` 来封装任务执行所需的项目规格（如 PRD、TechStack）、执行目录及修改指令。

#### Scenario: 任务成功执行
- **WHEN** 用户发起 `generate` 或 `modify` 请求
- **THEN** `AgentOrchestrator` 按顺序调度各个 `AgentStep`，共享同一个上下文，并在最终成功生成 Zip 产物后将任务状态标记为 `finished`。

### Requirement: 持久化并应用修改指令
The system SHALL 在 `TaskEntity` 中持久化用户的修改指令，并在执行具体生成步骤时，将其作为上下文参数传递给模型服务，以影响生成的代码内容。

#### Scenario: 修改任务指令生效
- **WHEN** 用户调用 `/api/task/{taskId}/modify` 并提供 `changeInstructions`
- **THEN** 系统将指令存入新建的 `TaskEntity` 中，随后 `AgentOrchestrator` 会将其加载到上下文中，生成后端/前端/SQL 代码时会将该指令加入 Prompt，生成符合要求的新代码。

## MODIFIED Requirements
### Requirement: 任务执行入口改造
原有 `TaskExecutorService.executeTask` 的巨大 switch-case 逻辑被移除，改为调用 `agentOrchestrator.run(taskId)`。原有的 `saveFile`, `generateFiles`, `packageArtifacts` 等辅助方法被分配到具体的 `AgentStep` 实现中。

## REMOVED Requirements
### Requirement: 串行硬编码逻辑
**Reason**: 不利于未来扩展新的 Agent 步骤（如自动化测试步骤等），且阻碍了错误重试与故障恢复机制的引入。
**Migration**: 所有的生成逻辑全部迁移至对应的 `AgentStep` 接口实现类中。
