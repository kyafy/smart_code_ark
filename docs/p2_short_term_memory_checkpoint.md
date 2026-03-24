# P2 短期记忆接入（checkpoint）

## 目标

- 在同一任务内，步骤之间可读取前序执行记忆。
- 维度遵循：`taskId + stepCode + sequence`。

## 存储位置

- 文件：`<workspaceRoot>/<taskId>/.smartark/checkpoints.jsonl`
- 格式：每行一个 JSON 记录（append-only）。

## 记录字段

- `taskId`
- `stepCode`
- `sequence`
- `promptSummary`
- `outputSummary`
- `failureReason`
- `fixedActions`
- `createdAt`

## 当前读写时机

1. 每个 step 执行前读取最近 8 条记录并挂载到 `AgentExecutionContext.shortTermMemories`。
2. 每个 step 执行后写入一条 checkpoint（成功/失败均写）。

## 可观测字段

- `memory_probe event=load ... entries=<n>`
- `memory_probe event=write ... sequence=<n> hasFailure=<true|false>`
