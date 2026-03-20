# Tasks

- [x] Task 1: 对齐预览契约与配置基线
  - [x] SubTask 1.1: 扩展后端 `TaskPreviewResult` 字段为 `taskId/status/previewUrl/expireAt/lastError`
  - [x] SubTask 1.2: 对齐前端 `types/api.ts` 与 `api/endpoints.ts` 的预览响应类型
  - [x] SubTask 1.3: 在后端配置中补充 `preview.enabled`、`preview.autoDeployOnFinish`、`preview.defaultTtlHours`、`preview.maxConcurrentPerUser`

- [x] Task 2: 落地 Phase 1 数据层与查询接口
  - [x] SubTask 2.1: 新增数据库迁移创建 `task_preview` 表及三个索引
  - [x] SubTask 2.2: 新增 `TaskPreviewEntity` 与 `TaskPreviewRepository`
  - [x] SubTask 2.3: 改造 `GET /api/task/{taskId}/preview` 返回真实状态
  - [x] SubTask 2.4: 实现 Phase 1 无沙箱静态预览回写逻辑（仅静态 URL，不启动后端实例）

- [x] Task 3: 接入任务完成自动触发预览
  - [x] SubTask 3.1: 在 `AgentOrchestrator` 完成分支增加 `generate/modify + finished` 触发判断
  - [x] SubTask 3.2: 新增异步 `PreviewDeployService`，写回 `provisioning/ready/failed`
  - [x] SubTask 3.3: 增加部署开始/成功/失败/耗时日志

- [x] Task 4: 改造结果页预览状态机
  - [x] SubTask 4.1: 在 `TaskResultPage.vue` 实现 `provisioning` 轮询（2-3 秒）
  - [x] SubTask 4.2: 在 `ready` 渲染 iframe 与新窗口打开入口
  - [x] SubTask 4.3: 在 `failed/expired` 渲染异常提示并保持下载/修改功能不变
  - [x] SubTask 4.4: 接入前端内置 mock 数据策略用于无沙箱预览展示

- [x] Task 5: 落地 Phase 2 可恢复能力
  - [x] SubTask 5.1: 新增 `POST /api/task/{taskId}/preview/rebuild`
  - [x] SubTask 5.2: 实现仅 `failed/expired` 可重建的状态校验
  - [x] SubTask 5.3: 增加构建/启动/代理/超时错误码映射
  - [x] SubTask 5.4: 前端接入“重建预览”按钮、loading 与防重复提交

- [x] Task 6: 落地 Phase 3 资源治理
  - [x] SubTask 6.1: 增加过期扫描与回收定时任务
  - [x] SubTask 6.2: 回收后状态置为 `expired` 并记录异常日志
  - [x] SubTask 6.3: 实现每用户并发预览配额限制（默认 2）
  - [x] SubTask 6.4: 前端消费超限错误码并展示一致提示

- [x] Task 7: 落地 Phase 4 体验增强
  - [x] SubTask 7.1: 增加 `provisioning` 细分阶段文案
  - [x] SubTask 7.2: 引入 SSE 推送并在不可用时自动回退轮询

- [x] Task 8: 执行联调、回归与发布验收
  - [x] SubTask 8.1: 验证 generate/modify 自动触发与非目标类型跳过
  - [x] SubTask 8.2: 验证失败与过期重建流程
  - [x] SubTask 8.3: 验证下载 ZIP 与修改生成功能不回归
  - [x] SubTask 8.4: 验证鉴权隔离、灰度开关与回滚策略

# Task Dependencies
- Task 2 depends on Task 1
- Task 3 depends on Task 2
- Task 4 depends on Task 1, Task 2
- Task 5 depends on Task 3, Task 4
- Task 6 depends on Task 2, Task 3
- Task 7 depends on Task 4
- Task 8 depends on Task 5, Task 6, Task 7
