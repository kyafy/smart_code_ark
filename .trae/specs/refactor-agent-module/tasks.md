# Tasks

- [x] Task 1: 数据库与实体层改造
  - [x] SubTask 1.1: 编写 Flyway 迁移脚本 `V3__task_instructions.sql`，在 `tasks` 表中新增 `instructions TEXT NULL` 字段。
  - [x] SubTask 1.2: 更新 `TaskEntity`，增加 `instructions` 字段及其 getter/setter。
  - [x] SubTask 1.3: 更新 `TaskService.createAndStartTask`，将传入的 `instructions` 参数设置到 `TaskEntity` 中并保存。

- [x] Task 2: 配置项与基础结构支持
  - [x] SubTask 2.1: 在 `application.yml` 中添加 `smartark.agent.workspace-root: /tmp/smartark/` 配置。
  - [x] SubTask 2.2: 新建 `AgentExecutionContext` 类，包含任务、项目、规格、指令、工作目录、文件列表等属性及对应 Getter/Setter。
  - [x] SubTask 2.3: 新建 `AgentStep` 接口，定义 `String getStepCode()` 和 `void execute(AgentExecutionContext context) throws Exception;`。

- [x] Task 3: 核心 AgentStep 实现
  - [x] SubTask 3.1: 提取 `AbstractCodegenStep` 抽象类，提供注入 `ModelService`，包含公用的 `generateFiles` 与 `saveFile`（支持 `workspace-root` 路径）方法。需要修改 `ModelService.generateFileContent` 或在 Step 中组合 Prompt 以支持 `instructions` 参数。
  - [x] SubTask 3.2: 实现 `RequirementAnalyzeStep`，调用模型生成 `fileList`。
  - [x] SubTask 3.3: 实现 `CodegenBackendStep`，生成后端文件。
  - [x] SubTask 3.4: 实现 `CodegenFrontendStep`，生成前端文件。
  - [x] SubTask 3.5: 实现 `SqlGenerateStep`，生成数据库及配置文件。
  - [x] SubTask 3.6: 实现 `PackageStep`，将工作目录打包并持久化 `ArtifactEntity`。

- [x] Task 4: 任务编排与入口改造
  - [x] SubTask 4.1: 新建 `AgentOrchestrator` 类，实现上下文初始化（包括从 `TaskEntity` 中获取 `instructions`），以及按顺序循环执行 `AgentStep`，并统一处理日志和状态更新。
  - [x] SubTask 4.2: 改造 `TaskExecutorService`，移除原有的巨大 `switch-case` 以及相关私有方法，仅保留 `@Async` 调用 `AgentOrchestrator.run(taskId)` 的逻辑。

# Task Dependencies
- [Task 2] depends on [Task 1]
- [Task 3] depends on [Task 2]
- [Task 4] depends on [Task 3]
