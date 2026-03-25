# P2 长期记忆与 ContextAssembler

## 长期记忆（P2-2）

### 维度

- `projectId + userId + stackSignature`

### 存储

- 文件：`<workspaceRoot>/.smartark/longterm/<projectId>__<userId>__<stackSignature>.jsonl`
- 服务：`LongTermMemoryService`

### 读写策略

1. 每步执行前读取 Top-K（默认 8）作为长期记忆。
2. 每步执行后写入一条长期记忆：
   - 成功：`memoryType=success_pattern`
   - 失败：`memoryType=failure_pattern`

## 上下文拼装器（P2-3）

### 组件

- 服务：`ContextAssembler`

### 输入

- 当前 step
- 基础指令
- PRD 摘要
- 短期记忆列表
- 长期记忆列表

### 输出

- `contextPack`：可直接注入模型的拼装上下文
- `sources`：来源标记
- `shortTermCount` / `longTermCount`
- `truncated`：是否触发长度截断

### 长度控制

- 配置：`smartark.memory.context.max-chars`
- 默认：`4000`

### 可观测日志

- `memory_probe event=load_long_term ...`
- `context_probe ... sources=... shortTermCount=... longTermCount=... truncated=...`
