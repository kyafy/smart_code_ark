# Smart Code Ark v3.5 项目预览重构迭代需求文档

- 文档版本：v3.5
- 文档日期：2026-03-21
- 适用范围：`frontend-web`、`services/api-gateway-java`、预览运行环境
- 重构目标：让预览真实反映业务代码运行效果，提升可用性与可信度

## 1. 背景与问题

现状预览能力存在以下问题：
1. 预览链接与真实产物运行脱节，用户难以看到业务代码真实效果。
2. 当前页面预览更多是“占位展示/静态展示”，无法体现前后端联动。
3. 预览失败缺少可诊断信息，用户无法判断“代码问题”还是“环境问题”。
4. 预览触发后状态反馈粗糙，等待体验不透明。

## 2. 目标

1. 预览必须运行真实任务产物（至少前端真实运行，阶段二支持全栈）。
2. 仅在两个节点触发：`generate finished`、`modify finished`。
3. 提供可观测、可重建、可回收的预览生命周期管理。
4. 前端显示分阶段状态与失败原因，支持一键重建。

## 3. 重构范围

## 3.1 In Scope

1. 预览链路改为“任务产物 -> 运行环境部署 -> 网关暴露 -> 页面展示”。
2. 新增预览部署编排服务与状态机。
3. 新增预览日志查询与重建能力。
4. 引入预览运行时隔离与 TTL 回收策略。

## 3.2 Out of Scope

1. 任务执行过程中的实时热更新预览。
2. 多区域容灾与跨云调度。
3. 企业级预览权限体系（组织级共享策略）。

## 4. 现有链路与目标链路

## 4.1 现有链路（简化）

1. 任务完成后，前端调用 `/api/task/{taskId}/preview`
2. 后端返回预览地址（历史存在占位行为）
3. 前端 iframe 加载地址

问题：地址不稳定、真实业务运行不完整、失败不可诊断。

## 4.2 目标链路（重构后）

1. 任务 `finished` 且 `taskType in (generate, modify)` 触发预览部署事件。
2. `PreviewOrchestrator` 创建预览记录并进入 `provisioning`。
3. `PreviewRuntime` 拉起隔离容器并执行启动流程。
4. `PreviewGateway` 分配预览域名并完成反向代理。
5. 健康检查通过后状态置为 `ready`，返回真实 `previewUrl`。
6. 失败置为 `failed`，写入 `lastError` 与日志摘要。
7. 到期或主动回收置为 `expired`。

## 5. 预览状态机

状态：
1. `provisioning`
2. `ready`
3. `failed`
4. `expired`

阶段字段（新增 `phase`）：
1. `prepare_artifact`
2. `start_runtime`
3. `install_deps`
4. `boot_service`
5. `health_check`
6. `publish_gateway`

流转：
1. `provisioning -> ready -> expired`
2. `provisioning -> failed`
3. `failed/expired -> provisioning`（rebuild）

## 6. 接口设计（新增/改造）

## 6.1 查询预览状态（改造）

- `GET /api/task/{taskId}/preview`

返回示例：
```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "taskId": "t_xxx",
    "status": "provisioning",
    "phase": "install_deps",
    "previewUrl": null,
    "expireAt": null,
    "lastError": null,
    "updatedAt": "2026-03-21T12:00:00"
  }
}
```

## 6.2 重建预览（保留并增强）

- `POST /api/task/{taskId}/preview/rebuild`

增强点：
1. 返回最新状态与阶段。
2. 对重复重建请求做幂等处理。

## 6.3 查询预览日志（新增）

- `GET /api/task/{taskId}/preview/logs?tail=200`

返回示例：
```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "taskId": "t_xxx",
    "logs": [
      {"ts": 1710000000000, "level": "info", "message": "npm install started"},
      {"ts": 1710000003000, "level": "error", "message": "vite: command not found"}
    ]
  }
}
```

## 6.4 预览就绪回调（内部接口，可选）

- `POST /internal/preview/{taskId}/status`

用途：
1. 运行时异步回传 `status/phase/error`。
2. 解耦预览编排与运行执行器。

## 7. 数据模型调整

沿用并增强 `task_preview`：
1. 新增 `phase`（当前阶段）
2. 新增 `runtime_id`（容器/实例标识）
3. 新增 `build_log_url`（可选，外部日志存储）
4. 新增 `last_error_code`（结构化错误码）
5. 新增 `last_health_check_at`

索引建议：
1. `idx_status_updated(status, updated_at)`
2. `idx_user_status(user_id, status)`
3. `idx_expire_at(expire_at)`

## 8. 技术栈引入与选型

## 8.1 核心引入

1. 运行时容器：Docker（MVP）
2. 网关层：Nginx/Caddy/Traefik（任选一，推荐现网一致）
3. 事件编排：Spring 异步任务（后续可迁移队列）
4. 日志采集：应用日志 + 预览运行日志（可接入 Loki）

## 8.2 安全能力

1. 资源限制：CPU/内存/超时
2. 网络限制：默认最小外联，禁止危险权限
3. 域隔离：`preview-{taskId}.domain`
4. iframe 安全：`sandbox` + CSP

## 8.3 后续扩展（非 MVP）

1. K8s 调度
2. gVisor/Kata/Firecracker 强隔离
3. 预热池（Warm Pool）降低冷启动

## 9. 前端改造要求

页面：`TaskResultPage` / `PreviewPage`

1. 增加阶段可视化（phase 文案）
2. `provisioning` 轮询（2-3s）或 SSE 推送
3. `ready` 加载 iframe + 新窗口打开
4. `failed` 展示错误摘要 + 查看日志 + 重建按钮
5. `expired` 展示过期提示 + 重建按钮

## 10. 后端改造要求

模块建议：
1. `PreviewOrchestratorService`：状态机与触发编排
2. `PreviewRuntimeService`：容器拉起与健康检查
3. `PreviewGatewayService`：路由映射与预览地址发布
4. `PreviewLogService`：日志聚合与查询

关键改造点：
1. 在任务完成回调处触发预览部署（仅 generate/modify）
2. 改造 `TaskService.getPreview()` 返回真实状态数据
3. 增强 `rebuildPreview()` 幂等与并发保护
4. 新增预览日志接口与错误码映射

## 11. 迭代计划（可执行 Phase）

## 11.1 Phase 1（MVP，1-2 周）

1. 容器预览跑通（前端真实运行）
2. 状态机与阶段字段接入
3. 前端状态展示 + 重建能力
4. TTL 回收基础能力

验收：
1. 两个触发节点可稳定进入 `ready`
2. 失败可见原因并可重建

## 11.2 Phase 2（增强，1 周）

1. 全栈预览（前后端联动）
2. 预览日志接口与错误诊断增强
3. 预览网关子域路由规范化

验收：
1. 可看到真实业务接口交互效果
2. 常见失败可通过日志快速定位

## 11.3 Phase 3（优化，持续）

1. 启动性能优化（依赖缓存、预热池）
2. 并发治理与配额策略
3. 监控告警（成功率、耗时、失败原因）

## 12. 指标与验收口径

核心指标：
1. 预览成功率（`provisioning -> ready`）>= 90%
2. 首次可预览时间 P50 <= 120s
3. 重建成功率 >= 80%
4. 失败可诊断率 >= 95%

DoD：
1. 仅两个完成节点触发预览
2. 预览展示真实业务运行结果
3. 前后端状态与日志链路闭环
4. TTL 回收与并发限制生效

## 13. 风险与应对

1. 风险：冷启动慢
2. 应对：分阶段预热与依赖缓存
3. 风险：容器安全问题
4. 应对：最小权限运行 + 资源/网络限制
5. 风险：并发预览导致资源抢占
6. 应对：用户并发上限 + 队列排队 + 超时回收

