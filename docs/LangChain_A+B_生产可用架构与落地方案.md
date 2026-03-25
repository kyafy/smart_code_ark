# LangChain 方案 A+B 生产可用架构与落地方案

## 1. 目标与范围

本方案基于“方案 A（LangGraph Sidecar 侧车）+ 方案 B（质量门控嵌入现有编排）”，在不大改现有前端交互和主接口路径的前提下，提升代码生成稳定性、上下文一致性与可观测性。

目标：

1. 生成产物“可运行、可构建、可预览”默认达成。
2. 在现有 Java 主链路中引入可回放的记忆存储与上下文共享。
3. 建立质量门控（规则校验 + 自动修复 + 评估阈值）闭环。
4. 保持前端主交互不变，新增字段全部可选。

范围：

1. 覆盖生成任务链路：`requirement_analyze -> codegen_backend -> codegen_frontend -> sql_generate -> package`。
2. 覆盖预览链路：sandbox 运行 + 反向代理发布。
3. 覆盖测试：单元、接口、集成与回归门禁。

## 2. 现状基线与改造原则

现状基线：

1. 编排固定 5 步，入口在 `TaskService`。
2. `PackageStep` 已具备“缺失文件补齐 + compose context 修复”基础能力。
3. 预览链路存在 fallback 与 localhost 直出模式，代理网关未完全落地。

改造原则：

1. 兼容优先：保留 `/api/generate`、`/api/task/{taskId}/status`、`/api/task/{taskId}/preview`。
2. 小步发布：先接 Sidecar 与质量门，再切换代理发布。
3. 可回滚：每个阶段都可通过 feature flag 回退到当前实现。

## 3. 目标架构（A+B）

## 3.1 组件分层

1. **Gateway 编排层（Java，保留）**
   - 任务创建、状态管理、权限校验、日志记录。
2. **Generation 执行层（Java，增强）**
   - 原有步骤执行 + 新增质量门步骤。
3. **LangGraph Sidecar（Python/JS，新增）**
   - 记忆存储、上下文拼装、质量评估与修复建议。
4. **Delivery Guard（Java，增强）**
   - 合同校验、自动修复、报告落盘。
5. **Preview Runtime（Java，保留增强）**
   - 容器生命周期、健康检查、日志采集。
6. **Preview Gateway（新增）**
   - taskId 到 upstream 的反向代理路由注册/回收。

## 3.2 关键链路

1. 任务开始：Java 编排读取项目上下文 -> 调 Sidecar 请求“上下文增强包”。
2. 步骤执行：每步生成前注入短期记忆与长期记忆摘要。
3. 质量门控：生成后调用 Sidecar 执行规则评估，未达标触发自动修复或重试。
4. 打包前验收：`PackageStep` 生成 `contract_report.json` 与 `delivery_manifest.json`。
5. 预览发布：sandbox 启动通过后，Gateway 发布统一预览域名路由。

## 4. 记忆与上下文共享设计

## 4.1 短期记忆（thread/task scoped）

1. 维度：`taskId + stepCode + sequence`。
2. 内容：Prompt 输入摘要、模型输出摘要、关键失败原因、修复动作。
3. 用途：同一任务多步骤之间上下文衔接。

## 4.2 长期记忆（project/user scoped）

1. 维度：`projectId + userId + stackSignature`。
2. 内容：历史失败模式、成功模板、常见修复动作。
3. 用途：跨任务复用经验，减少重复错误。

## 4.3 Sidecar 存储建议

1. Checkpoint：Postgres 或 Redis（线程状态与执行快照）。
2. Long-term Store：Postgres + 向量索引（可选 Qdrant）。
3. TTL 策略：短期记忆 7~14 天，长期记忆按项目生命周期管理。

## 5. 质量门控设计

## 5.1 门控阶段

1. 结构门：关键文件存在、路径合法（禁止 `..`）。
2. 语义门：关键规则字符串与脚本命令存在。
3. 构建门：compose context 有效、启动脚本可执行。
4. 评估门：LangSmith 离线/轨迹评分达到阈值。

## 5.2 失败处理策略

1. 一级失败：自动修复（文件补齐、compose 修复）。
2. 二级失败：同步骤重试（限定次数）。
3. 三级失败：任务失败并输出可读报告与建议。

## 6. 接口设计（兼容 + 增量）

## 6.1 保持不变

1. `POST /api/generate`
2. `GET /api/task/{taskId}/status`
3. `GET /api/task/{taskId}/preview`

## 6.2 新增接口

1. `GET /api/task/{taskId}/contract-report`
   - 读取最新交付报告。
2. `POST /api/task/{taskId}/delivery/validate`
   - 请求体：`{ "autoFix": true|false }`
   - 同步返回校验与修复结果。

## 6.3 新增报告字段（最小集）

`contract_report.json`：

1. `passed: boolean`
2. `failedRules: string[]`
3. `fixedActions: string[]`
4. `generatedAt: string`

`delivery_manifest.json`：

1. `stack: string`
2. `entrypoints: string[]`
3. `services: string[]`
4. `runCommands: string[]`

## 7. 数据模型与持久化建议

建议新增表（可选，v2 可落）：

1. `task_delivery_reports`
   - `id, task_id, report_json, manifest_json, passed, created_at`
2. `preview_routes`
   - `id, task_id, runtime_id, public_path, upstream, status, expire_at, created_at`
3. `memory_records`
   - `id, scope_type, scope_id, key, value_json, vector_ref, created_at`

在 v1 中可先落盘到 workspace 并通过接口读取，后续再入库。

## 8. 生产部署拓扑

1. `api-gateway-java`：主服务（多副本）。
2. `langgraph-sidecar`：独立服务（水平扩展，按 QPS 扩容）。
3. `postgres`：任务元数据 + checkpoint/store。
4. `redis`：短期缓存与幂等键。
5. `preview-gateway`：统一反向代理层（Nginx/Caddy/Traefik）。
6. `sandbox runtime`：按任务拉起临时容器。

## 9. 安全与合规

1. Sidecar 与 Java 间使用内部鉴权（mTLS 或签名 header）。
2. 记忆存储脱敏（手机号、token、密钥字段剔除）。
3. 预览 URL 支持短期 ticket，默认过期回收。
4. 日志与报告禁止写入明文凭据。

## 10. 观测与 SLO

核心指标：

1. 生成成功率（按任务类型、按模型）。
2. 一次通过率（无需修复直接通过）。
3. 自动修复成功率。
4. 预览可达率与首可用时延。

SLO 建议：

1. 生成任务成功率 >= 95%。
2. 关键产物合同通过率 >= 98%。
3. 预览发布可达率 >= 99%。

## 11. 分阶段落地计划

## Phase P0（1 周）：基线接入与开关

1. 新增 `langchain.enabled`、`delivery.guard.enabled` 开关。
2. 接入 Sidecar 健康检查与调用 SDK。
3. 完成最小链路打通（不改变业务行为）。

验收：

1. 开关关闭时行为与当前一致。
2. 开关开启时可拿到 Sidecar 回包。

## Phase P1（1~2 周）：Delivery Guard v1

1. 在 `PackageStep` 生成并落盘：
   - `contract_report.json`
   - `delivery_manifest.json`
2. 增加接口：
   - `GET /api/task/{taskId}/contract-report`
   - `POST /api/task/{taskId}/delivery/validate`
3. 增加权限校验（仅任务所有者可读写）。

验收：

1. 报告字段满足最小集。
2. autoFix 生效并返回修复动作列表。

## Phase P2（1~2 周）：记忆与上下文共享

1. 短期记忆：按 task/thread checkpoint 接入。
2. 长期记忆：按 project/user 读写摘要。
3. 生成前上下文拼装（Top-K 记忆注入）。

验收：

1. 同类任务重复失败率下降。
2. 上下文注入可追踪（日志可见来源与条数）。

## Phase P3（1~2 周）：质量门控闭环

1. 嵌入 `quality_gate` 子步骤（可先内聚到现有步骤后）。
2. 规则评估 + 自动修复 + 限次重试。
3. LangSmith 离线评估接入 CI 门禁。

验收：

1. 不达标任务可拦截，不进入最终打包。
2. CI 中可输出质量分与失败样例。

## Phase P4（2 周）：真实预览发布

1. 引入 `PreviewGatewayService` 与路由注册表。
2. previewUrl 切换为统一域名。
3. 到期自动回收容器与路由。

验收：

1. 前端无需改主流程即可使用新 URL。
2. fallback 占位路径在灰度后逐步下线。

## 12. 测试与发布门禁

单元测试：

1. `PackageStepTest`：缺失/错误文件修复 + 报告断言。
2. `TaskService`：报告不存在、权限不足、任务未完成。
3. `PromptResolverTest`、`PromptRenderer` 快照测试（规则字串存在校验）。

接口测试：

1. `TaskController` 新接口：
   - `contract-report`
   - `delivery/validate`

集成测试：

1. 生成 -> 校验 -> 修复 -> 打包 -> 预览 全链路。

## 13. 回滚策略

1. 通过 feature flag 一键关闭 Sidecar 增强能力。
2. 保留现有 `PackageStep` 最小兜底逻辑。
3. 预览网关切换失败时回退到 localhost 直出模式。

## 14. 里程碑与交付物

1. M1：P1 完成，交付报告能力上线。
2. M2：P2 完成，记忆与上下文共享上线。
3. M3：P3 完成，质量门控 CI 化。
4. M4：P4 完成，真实预览代理上线。

交付物：

1. 架构设计文档（本文件）。
2. 接口契约文档与错误码清单。
3. 测试报告与灰度发布记录。
