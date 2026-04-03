# 双引擎功能边界与状态协同规范

## 1. 目标

在 DeepAgent 与 Java 双链路下，明确职责边界、状态转移机制、重试与恢复协议，避免职责重叠、链路冲突与状态失真。

## 2. 功能边界

### 2.1 DeepAgent 主责

- 节点内内容生产：规划、生成、迭代、修复。
- 节点内模型调用编排：多轮推理、反思、收敛。
- Sandbox 执行：构建、运行、烟测、热修复。
- 节点结果构造：产物清单、构建报告、错误摘要。

### 2.2 Java 主责

- 任务与步骤状态真相：`tasks / task_steps / task_logs`。
- 内部回调鉴权与安全边界：`X-Internal-Token`。
- 平台能力聚合：模板解析、模型能力、RAG、论文能力、预览映射。
- 对外交互与审计：前端 API、历史追踪、可观测汇总。

### 2.3 可降级与不可降级接口

- 不可降级（必须成功回写）  
  - `/api/internal/task/{taskId}/step-update`
  - `/api/internal/task/{taskId}/log`
  - `/api/internal/preview/{taskId}/sandbox-ready`（启用预览时）

- 可降级（超时可继续）  
  - `/api/internal/model/structure`
  - `/api/internal/model/generate-file`
  - `/api/internal/template/resolve`
  - `/api/internal/rag/*`、`/api/internal/academic/*`（按节点需求）

## 3. 节点状态机协同协议

每个节点遵循统一生命周期：

- `pending` → `running` → `finished | failed`
- DeepAgent 作为执行者，Java 作为状态记录者。

### 3.1 节点启动

1. DeepAgent 发 `step-update(status=running, progress=10)`  
2. Java 写入 step 状态，更新 task 当前步骤。  
3. DeepAgent 开始节点内迭代。

### 3.2 节点进行中

1. DeepAgent 定期发 `step-update(progress=x)`。  
2. DeepAgent 通过 `/log` 回写关键里程碑。  
3. Java 不主动改写 DeepAgent 计算结果，只负责状态和审计。

### 3.3 节点结束

- 成功：`step-update(status=finished, progress=100, output_summary/output)`  
- 失败：`step-update(status=failed, error_code/error_message)`

## 4. Java 触发“从某节点重试”时的协同

## 4.1 触发语义

Java 端触发“从 stepCode 重试”时，应做以下原子操作：

- 标记目标 step 为待重跑。
- 清理后续 step 的运行态残留。
- 追加系统日志：`Manual retry from step: {stepCode}`。

## 4.2 DeepAgent 接收与执行

- DeepAgent 不直接消费“UI 重试事件”，而是消费“新的 dispatch 调度”。
- 新调度携带 taskId 与当前上下文，由 DeepAgent 重新从指定节点进入状态机。
- 若存在历史内存，按“短期记忆 + 长期记忆”加载；若冲突，以最新任务上下文为准。

## 4.3 幂等约束

- 同一 taskId + stepCode 的 `running -> finished/failed` 必须可重复提交而不破坏最终状态。
- 重试批次建议引入 `run_id`，用于区分多次尝试日志与指标。

## 5. 双链路冲突处理

## 5.1 冲突类型

- 内容冲突：DeepAgent 产物与 Java 工具产物不一致。
- 状态冲突：同一步骤出现并发回写。
- 顺序冲突：后续步骤先于前置步骤完成回写。

## 5.2 处理规则

- 内容冲突：以“节点最终提交版本”为准，并记录冲突摘要。
- 状态冲突：按时间戳 + 合法状态流转校验，非法回写拒绝。
- 顺序冲突：Java 侧拒绝越序 finished，强制回退为 pending/running。

## 5.3 内容主导权强约束

- 节点内容主导权归 DeepAgent，Java 不得直接覆盖节点最终产物。
- Java 增强工具输出仅作为“建议增强”，不得作为强制替换。
- 节点最终提交必须携带 `producer=deepagent` 与 `run_id`，用于审计追踪。
- Java 可触发“从某节点重试”，但不能注入“改写后的内容正文”。
- 若增强结果与 DeepAgent 结果冲突，按以下顺序处理：
  - 规则校验结论优先
  - DeepAgent 最终提交版本优先
  - Java 仅记录冲突摘要，不改写正文

## 5.4 状态与内容分离策略

- 状态面（Java 主导）：
  - `step-update / log / preview` 回写
  - 任务进度、失败码、审计日志落库
- 内容面（DeepAgent 主导）：
  - 节点草稿、迭代版本、最终输出
  - 工作区产物文件与清单
- `step-update.output` 只记录摘要、清单、指标，不存放“可被 Java 二次改写”的正文主文档。

## 6. 失败分级

- `tool_timeout`：可降级继续。
- `validation_failed`：可迭代重试。
- `callback_failed`：不可降级，节点失败。
- `fatal_runtime_error`：任务失败并要求人工介入。

## 7. 统一术语

- 双引擎：DeepAgent（执行引擎） + Java（治理引擎）
- 节点内迭代：同一节点内多轮模型与规则闭环
- 降级执行：外部增强失败后使用内置保底路径继续
- 状态真相：以 Java 持久层中的 task/step/log 为准
