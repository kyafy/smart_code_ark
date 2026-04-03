# 节点级观测指标与日志规范

## 1. 目标

在每个节点结束时，统一记录以下指标，支持问题定位、容量评估、质量回归：

- 本次迭代轮次
- 模型调用次数
- 拆分任务数
- 结果清单
- token 消耗

## 2. 指标模型

每个节点输出统一 `node_metrics`：

```json
{
  "task_id": "string",
  "run_id": "string",
  "node": "requirement_analyze",
  "round": 2,
  "status": "finished",
  "duration_ms": 12345,
  "model_calls": 6,
  "subtasks_total": 3,
  "subtasks_finished": 3,
  "result_items": [
    "frontend/src/views/Dashboard.vue",
    "backend/src/main/java/.../UserController.java"
  ],
  "tokens": {
    "prompt": 10234,
    "completion": 4301,
    "total": 14535
  },
  "tool_calls": {
    "java_api": 2,
    "sandbox": 1
  },
  "degrade": false,
  "error_code": "",
  "error_message": ""
}
```

## 3. 必填字段

- `task_id`
- `run_id`
- `node`
- `status`
- `duration_ms`
- `round`
- `model_calls`
- `subtasks_total`
- `result_items`
- `tokens.total`

## 4. 采集口径

### 4.1 迭代轮次

- 每次“生成→评估→校验”完整闭环 +1。
- 首轮从 1 开始，不允许 0。

### 4.2 模型调用次数

- 统计节点内所有 LLM 调用（含重写、自评、修复）。
- 不含纯规则检查。

### 4.3 拆分任务数

- 统计该节点拆出的并行子任务总数。
- 子任务失败也计入总数。

### 4.4 结果清单

- requirement_analyze：文件计划路径清单
- codegen_*：实际生成文件清单
- build_verify：执行命令与报告清单

### 4.5 token 消耗

- 以 provider 返回 usage 为准。
- 聚合到节点级总和。

## 5. 落库与回写

- 深度日志：LangSmith（trace 级）
- 平台日志：task_logs（摘要级）
- 节点指标：建议新增 `task_step_metrics` 表或 JSON 字段挂载到 step output

## 6. 日志级别规范

- `info`：节点开始/完成、关键里程碑
- `warn`：降级、超时重试、部分检查未通过
- `error`：节点失败、不可恢复异常

## 7. 关键告警规则

- `node_timeout_rate > 阈值`
- `degrade_ratio > 阈值`
- `tokens.total` 异常飙升
- `subtasks_total - subtasks_finished` 长期不为 0

## 8. 面板建议

- 节点耗时分位（P50/P90/P95）
- 节点成功率与失败码分布
- 节点 token 成本趋势
- 降级触发率趋势
- 子任务并发效率（完成率/平均耗时）
