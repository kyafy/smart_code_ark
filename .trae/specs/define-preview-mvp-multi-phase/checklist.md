* [x] 预览查询接口返回 `taskId/status/previewUrl/expireAt/lastError`，不再是固定链接占位

* [x] 仅 `generate/modify` 且 `finished` 时触发预览部署，且触发为异步

* [x] `task_preview` 表与 `uk_task_id`、`idx_user_status`、`idx_expire_at` 索引生效

* [x] 无沙箱 Phase 1 下预览 URL 可访问静态页面，页面由前端内置 mock 数据驱动

* [x] 结果页在 `provisioning` 时轮询，在 `ready` 显示 iframe，在 `failed/expired` 显示异常态

* [x] 下载 ZIP 与修改生成功能在预览改造后保持可用

* [x] `POST /api/task/{taskId}/preview/rebuild` 仅允许 `failed/expired`，并能恢复到 `provisioning`

* [x] 错误码至少覆盖构建失败、启动失败、代理失败、超时失败

* [x] 过期回收任务可将预览状态置为 `expired` 并记录日志

* [x] 每用户并发预览配额限制生效，超限提示前后端一致

* [x] `provisioning` 细分阶段反馈可见，SSE 不可用时自动回退轮询

* [x] 鉴权隔离生效，跨用户不可访问他人预览

* [x] 灰度开关与回滚策略可用：`preview.enabled`、`preview.autoDeployOnFinish`

