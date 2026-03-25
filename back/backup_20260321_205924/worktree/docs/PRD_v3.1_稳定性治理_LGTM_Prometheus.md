# Smart Code Ark v3.1 稳定性治理需求文档（LGTM + Prometheus）

- 文档版本：v3.1
- 文档日期：2026-03-21
- 适用范围：`services/api-gateway-java`、`frontend-web`、MySQL、Redis、任务系统、预览系统
- 目标：建立“可观测 + 告警 + 故障响应”闭环，支撑业务稳定增长

## 1. 背景与目标

当前系统具备核心业务能力（聊天、生成任务、预览、计费），但在稳定性层面仍缺少统一体系：

1. 缺少业务级监控（任务成功率、任务耗时、卡住率、预览成功率）。
2. 缺少分级告警与降噪机制。
3. 缺少日志-指标-链路统一排障入口。
4. 缺少可度量的 SLO（服务等级目标）与稳定性验收标准。

v3.1 目标：

1. 上线 LGTM + Prometheus 体系并接入业务。
2. 完成任务系统、预览系统、计费系统的关键监控与告警。
3. 建立稳定性治理流程：观测 -> 告警 -> 响应 -> 复盘 -> 优化。

## 2. 技术架构

## 2.1 目标架构

1. 指标：Spring Boot Actuator + Micrometer -> Prometheus
2. 告警：Prometheus Rule -> Alertmanager -> 飞书/钉钉/企微/邮件
3. 可视化：Grafana 统一仪表盘与告警看板
4. 日志：应用日志 -> Grafana Alloy -> Loki
5. 链路：Micrometer Tracing / OpenTelemetry -> Tempo

## 2.2 架构原则

1. 先指标告警，再日志关联，再链路深化（分阶段上线）。
2. 监控优先覆盖用户可感知症状，不先做底层噪音告警。
3. 业务指标与系统指标并重，任务类业务作为一等公民。
4. 避免高基数标签，控制监控系统成本与性能风险。

## 3. 稳定性治理范围

## 3.1 In Scope

1. API 网关服务可用性、延迟、错误率监控。
2. 任务系统监控（任务状态、步骤耗时、重试、失败原因）。
3. 预览系统监控（部署成功率、准备时长、过期清理）。
4. 计费与充值链路监控（扣费失败、回调失败、幂等冲突）。
5. MySQL、Redis、主机/容器基础资源监控。
6. 告警分级（P1/P2/P3）与通知策略。

## 3.2 Out of Scope（本期不做）

1. 全量自动根因定位（AIOps）。
2. 多机房/跨云灾备治理。
3. 全链路容量压测平台建设。

## 4. 关键指标体系

## 4.1 系统级指标（基础）

1. `http_server_requests_seconds`（API 延迟）
2. `http_server_requests_total`（QPS、状态码分布）
3. JVM：内存、GC、线程、CPU、类加载
4. DB：连接数、慢查询、主从延迟（如有）
5. Redis：连接、内存、命中率、阻塞
6. 主机：CPU、内存、磁盘、网络

## 4.2 业务级指标（重点）

任务域：
1. `task_created_total{task_type}`
2. `task_finished_total{task_type,status}`
3. `task_duration_seconds{task_type}`（Histogram）
4. `task_step_duration_seconds{step_code}`（Histogram）
5. `task_retry_total{step_code}`
6. `task_fail_total{step_code,error_code}`
7. `task_stuck_total`
8. `task_running_gauge`

预览域：
1. `preview_deploy_total{status}`
2. `preview_provision_seconds`
3. `preview_active_gauge`
4. `preview_expired_total`

计费域：
1. `billing_deduct_total{status,scene}`
2. `recharge_order_total{status}`
3. `recharge_callback_total{status}`
4. `recharge_idempotent_hit_total`

## 4.3 指标标签规范

允许标签：
1. `service`、`env`、`task_type`、`step_code`、`status`、`scene`

禁止高基数标签：
1. `task_id`、`session_id`、`user_id`、`trace_id`、`request_id`

## 5. 告警体系设计

## 5.1 告警分级

1. P1：用户核心功能不可用或大面积失败（5分钟内必须响应）
2. P2：核心功能降级或显著性能劣化（30分钟内响应）
3. P3：容量/趋势/低风险异常（工作时段处理）

## 5.2 首批告警规则（MVP）

P1：
1. API 5xx 错误率 > 5% 持续 5m
2. 任务失败率 > 20% 持续 10m
3. 预览部署成功率 < 70% 持续 15m
4. 充值回调失败率 > 10% 持续 10m

P2：
1. P95 API 延迟 > 2s 持续 10m
2. 任务平均耗时较基线上升 > 100% 持续 15m
3. Redis 内存使用 > 85% 持续 15m
4. MySQL 活跃连接 > 80% 持续 10m

P3：
1. 磁盘使用 > 80%
2. 任务重试率异常升高
3. 预览过期清理延迟

## 5.3 告警降噪

1. 设置 `for` 时长，过滤瞬时抖动。
2. Alertmanager 分组：按 `alertname + env + service` 分组。
3. 抑制规则：根告警触发时抑制下游重复告警。
4. 静默机制：发布窗口可临时静默指定告警组。

## 6. 仪表盘规划（Grafana）

## 6.1 Dashboard 列表

1. 平台总览（可用性、错误率、延迟、告警数）
2. 任务系统看板（创建、成功率、耗时、失败分布）
3. 预览系统看板（部署、就绪、失败、过期）
4. 计费系统看板（扣费、订单、回调、幂等）
5. 基础设施看板（JVM、MySQL、Redis、Host）

## 6.2 钻取链路

1. 指标异常 -> 跳转相关 Loki 日志
2. 指标异常 -> 跳转相关 Tempo trace
3. 告警信息附带 Runbook 链接

## 7. 迭代计划（可执行 Phase）

## 7.1 Phase 0：平台搭建（1 周）

目标：监控平台可运行可访问。

任务：
1. 部署 Prometheus、Alertmanager、Grafana、Loki、Tempo、Alloy。
2. 接入基础 exporter：node、mysql、redis。
3. 打通 Grafana 数据源。

验收：
1. 平台组件健康，采集链路畅通。
2. 基础资源指标可在 Grafana 展示。

## 7.2 Phase 1：应用指标接入（1-2 周）

目标：接入 API 服务与任务业务指标。

后端任务：
1. 暴露 `/actuator/prometheus` 并完成安全策略。
2. 增加任务、预览、计费业务指标埋点。
3. 统一 traceId 注入与日志关联字段。

前端任务：
1. 管理端（或内部页面）增加稳定性看板入口（可选）。
2. 告警状态展示（只读）入口（可选）。

验收：
1. 关键业务指标完整上报。
2. Prometheus 可稳定抓取，无大面积丢点。

## 7.3 Phase 2：告警上线（1 周）

目标：形成可用告警体系。

任务：
1. 编写并上线 P1/P2/P3 告警规则。
2. Alertmanager 路由、分组、抑制、静默策略上线。
3. 配置通知渠道（飞书/钉钉/企微/邮件）。

验收：
1. 告警可触发、可收敛、可静默。
2. 告警误报率控制在可接受范围。

## 7.4 Phase 3：日志与链路增强（1-2 周）

目标：提升定位效率。

任务：
1. 结构化日志接入 Loki，字段标准化。
2. 接入 Tempo，建立 trace-log 关联跳转。
3. 建立常见故障排障面板与 Runbook。

验收：
1. 从告警到定位闭环时间显著下降。
2. 关键故障可在 15 分钟内定位到模块级别。

## 8. 角色与分工

1. 后端：指标埋点、链路上下文、告警规则、Runbook
2. 前端：稳定性入口、状态呈现（可选）
3. 运维/平台：监控平台部署、权限、安全、备份、升级
4. 测试：告警触发验证、阈值回归、故障演练

## 9. 非功能与安全要求

1. 监控端点不对公网暴露，走内网或鉴权网关。
2. 日志脱敏：token、手机号、隐私字段必须脱敏。
3. 数据保留策略：
4. 指标：15-30 天热数据（可按容量调整）
5. 日志：7-15 天
6. Trace：3-7 天（错误链路可延长）

## 10. 验收标准（DoD）

1. 监控平台稳定运行并覆盖核心服务。
2. 任务/预览/计费三大业务域均有可视化与告警。
3. P1 告警可在 5 分钟内触达值班人员。
4. 从告警到定位平均耗时较当前下降 >= 50%。
5. 完成至少 2 次故障演练与复盘报告。

## 11. 风险与应对

1. 风险：高基数标签导致 Prometheus 压力激增。
2. 应对：建立指标评审规范，禁止高基数字段入 label。
3. 风险：告警风暴导致值班疲劳。
4. 应对：分级、抑制、静默与阈值复盘机制。
5. 风险：日志量过大导致成本上升。
6. 应对：采样、分级保留、冷热分层。

## 12. 交付物清单

1. 监控平台部署配置（IaC 或部署脚本）
2. 指标字典（含口径与标签规范）
3. 告警规则与路由配置
4. Grafana 看板与权限配置
5. 稳定性 Runbook 与故障演练记录

