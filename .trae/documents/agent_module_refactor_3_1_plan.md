# Agent 模块拆分详细设计方案 (3.1)

## 1. 摘要 (Summary)

本计划旨在详细展开“Agent 模块重构”中的第 3.1 节：后端模块拆分。通过引入策略模式（Strategy Pattern）和上下文（Context Pattern），将目前 `TaskExecutorService` 中硬编码的 `switch-case` 串行执行逻辑，重构为高内聚、低耦合的 `AgentOrchestrator` 与 `AgentStep` 体系。此举不会改变任何 Controller 或 DTO 的对外契约，确保前端 API 兼容性。

## 2. 当前状态分析 (Current State Analysis)

* **代码耦合度高**：在 [TaskExecutorService.java](file:///e:/fuyao/project/smart_code_ark/services/api-gateway-java/src/main/java/com/smartark/gateway/service/TaskExecutorService.java) 中，`executeTask` 方法内部使用 `switch (step.getStepCode())` 处理不同的生成步骤。

* **状态传递困难**：例如 `fileList` 作为一个局部变量在不同的 `case` 块之间传递，如果未来步骤增加或变为分布式执行，这种方式将无法维持。

* **职责不清晰**：`TaskExecutorService` 既负责任务状态流转（DB 操作），又负责具体的业务逻辑（如文件写入、ZIP 打包、调用模型）。

## 3. 拟议变更 (Proposed Changes)

我们将新增 `com.smartark.gateway.agent` 包，并将逻辑拆分如下：

### 3.1 `AgentExecutionContext` (Agent 执行上下文)

* **文件路径**：`services/api-gateway-java/src/main/java/com/smartark/gateway/agent/AgentExecutionContext.java`

* **职责**：作为贯穿整个任务生命周期的数据载体。

* **核心字段**：

  * `String taskId`

  * `String projectId`

  * `String prd` (需求文档内容)

  * `String fullStack` (技术栈描述)

  * `String changeInstructions` (修改指令，目前可预留)

  * `String workDir` (工作目录，如 `/tmp/smartark/{taskId}`)

  * `List<String> fileList` (结构规划产出的文件列表)

* **行为**：提供便捷的 Getter/Setter 供各 Step 读写共享状态。

### 3.2 `AgentStep` 接口及实现类

* **接口路径**：`services/api-gateway-java/src/main/java/com/smartark/gateway/agent/AgentStep.java`

* **方法设计**：

  * `String getStepCode();` // 返回如 "requirement\_analyze"

  * `void execute(AgentExecutionContext context) throws Exception;`

* **具体实现类** (作为 Spring `@Component` 注入所需服务，如 `ModelService`，`ArtifactRepository` 等)：

  * `RequirementAnalyzeStep`：调用模型生成 `fileList` 并存入 context。

  * `CodegenBackendStep`：遍历 `fileList` 过滤 backend 文件并生成。

  * `CodegenFrontendStep`：遍历 `fileList` 过滤 frontend 文件并生成。

  * `SqlGenerateStep`：遍历 `fileList` 过滤 database 及配置化文件并生成。

  * `PackageStep`：将 `context.getWorkDir()` 目录打包并生成 `ArtifactEntity`。

* **抽象基类提取** (可选但推荐)：`AbstractCodegenStep`，将公共的 `generateFiles` 与 `saveFile` 逻辑抽取，供 Backend/Frontend/Sql Step 复用。

### 3.3 `AgentOrchestrator` (任务编排器)

* **文件路径**：`services/api-gateway-java/src/main/java/com/smartark/gateway/agent/AgentOrchestrator.java`

* **职责**：控制任务的生命周期（初始化上下文、流转状态、异常捕获、记录日志）。

* **实现逻辑**：

  * 通过构造函数注入 `List<AgentStep>`，并在内部转化为 `Map<String, AgentStep>` 以便按 `stepCode` 快速查找。

  * 提供 `void run(String taskId)` 方法，内部查询 `TaskEntity` 和 `ProjectSpecEntity`，初始化 `AgentExecutionContext`。

  * 循环遍历 `TaskStepEntity`，更新状态为 `running`，通过 `stepMap.get(step.getStepCode()).execute(context)` 调用对应逻辑，完成后更新状态为 `finished`。

  * 提供统一的 `log` 记录方法。

### 3.4 改造 `TaskExecutorService`

* **文件路径**：`services/api-gateway-java/src/main/java/com/smartark/gateway/service/TaskExecutorService.java`

* **职责**：退化为异步执行的入口适配层。

* **修改内容**：

  * 移除原有的 `switch-case` 逻辑及私有辅助方法 (`generateFiles`, `saveFile`, `packageArtifacts`)。

  * 注入 `AgentOrchestrator`。

  * 在 `@Async` 注解的 `executeTask` 方法中，直接调用 `agentOrchestrator.run(taskId)`。

## 4. 假设与决策 (Assumptions & Decisions)

* **事务边界**：步骤执行不应包裹在一个巨大的数据库事务中，以防止长连接超时。状态更新应当单独提交。

* **向后兼容**：本次重构仅涉及内部架构的变动，数据库表结构（如 `tasks`, `task_steps`）与现有的 `step_code` ("requirement\_analyze", "codegen\_backend" 等) 保持完全一致。

* **上下文初始化**：上下文所需的数据目前从 `ProjectSpecEntity` 的 `requirement_json` 中提取，这部分解析逻辑将从原 `TaskExecutorService` 迁移至 `AgentOrchestrator` 的准备阶段。

## 5. 验证步骤 (Verification)

1. 编译验证：确保重构后项目成功启动，没有 Spring 循环依赖或注入失败。
2. 流程验证：触发 `/api/generate` 接口，观察后端日志是否正常进入 `AgentOrchestrator` 并按序流转至各个 `AgentStep`。
3. 产物验证：任务完成后，检查 `/tmp/smartark/{taskId}.zip` 是否正常生成，且下载后的内容与重构前一致。
4. 状态验证：在前端检查进度条是否能正常推进至 100% 并显示为完成。

