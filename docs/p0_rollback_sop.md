# P0 回滚 SOP（灰度前置）

## 回滚目标

- 快速关闭新增链路，恢复至主干稳定行为。
- 不改接口路径，不影响 `/api/generate`、`/api/task/{taskId}/status`、`/api/task/{taskId}/preview`。

## 回滚开关

1. `LANGCHAIN_ENABLED=false`
2. `DELIVERY_GUARD_ENABLED=false`
3. `PREVIEW_GATEWAY_ENABLED=false`

## 回滚步骤

1. 在灰度环境先更新配置中心或环境变量，设置上述 3 个开关值。
2. 滚动重启 `api-gateway-java` 实例。
3. 验证健康检查和关键接口：
   - `POST /api/generate`
   - `GET /api/task/{taskId}/status`
   - `GET /api/task/{taskId}/preview`
4. 验证日志关键字：
   - 无 Sidecar 失败阻断日志
   - `PackageStep` 出现 `Delivery guard disabled` 提示
   - 预览流程可继续执行

## 验证标准

- 核心三接口成功率恢复到回滚前基线。
- 任务执行轨迹可通过 `task_probe` / `step_probe` 日志完整还原。
- 无新增 5xx 异常波动。

## 演练记录建议

- 演练时间、执行人、环境、回滚耗时、结果截图（日志/监控）。
