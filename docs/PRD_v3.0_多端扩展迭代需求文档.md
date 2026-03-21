# Smart Code Ark v3.0 多端扩展迭代需求文档

- 文档版本：v3.0
- 文档日期：2026-03-21
- 文档目标：在现有 PC Web 基础上扩展到小程序与 App，形成统一产品能力与可持续迭代架构
- 关联仓库：`frontend-web`、`services/api-gateway-java`

## 1. 现状与问题分析（基于代码）

## 1.1 前端现状

1. 当前仅有一个 Web 前端工程：`frontend-web`（Vue3 + TypeScript + Vite + Pinia + Vue Router）。
2. 业务 API 访问集中在 `frontend-web/src/api/http.ts` 与 `frontend-web/src/api/endpoints.ts`，有较好的接口封装基础。
3. 聊天流式能力通过 `requestSse`（`fetch + ReadableStream`）实现，适配浏览器环境。
4. 页面路由主要为 `/projects`、`/chat/:sessionId`、`/tasks/...` 等 Web 路由，暂无多端路由组织。
5. UI 组件依赖 Element Plus，移动端/小程序不可直接复用。

## 1.2 后端现状

1. 统一后端网关：`services/api-gateway-java`（Spring Boot 3.4.4 + Java 17）。
2. 鉴权方式为 `Authorization: Bearer <token>`（`AuthInterceptor`）。
3. 已有用户、聊天、项目、任务、计费等核心领域接口，具备多端复用价值。
4. 聊天已支持 SSE 流式（`ChatController#chat` 返回 `SseEmitter`）。
5. 充值回调被鉴权白名单放行（`/api/billing/recharge/callback`），但充值业务能力仍处于在建状态。

## 1.3 关键差距

1. 缺少统一“多端能力分层”与端侧 SDK。
2. 流式回复目前偏 Web 实现，小程序与 App 兼容策略未定义。
3. 缺少设备/平台维度能力：版本控制、埋点、灰度、推送、上传策略等。
4. UI 与页面结构未按“跨端复用 + 端特性”拆分。

## 2. 建设目标

## 2.1 业务目标

1. 在 1 个大版本内完成 Web + 小程序 + App 三端可用。
2. 核心业务链路跨端一致：登录 -> 对话 -> 确认 -> 生成任务 -> 预览/下载 -> 账户充值。
3. 通过统一后端与统一业务域模型，降低后续维护成本。

## 2.2 技术目标

1. 建立“领域层复用、端适配层隔离”的前端工程体系。
2. 保持后端 API 主体不分叉，以能力扩展替代接口复制。
3. 定义流式回复的跨端协议降级路径（SSE -> WebSocket/轮询）。

## 3. 多端技术方案

## 3.1 方案对比（结合现有 Vue 技术栈）

1. 方案A：`uni-app (Vue)` 承载小程序 + App，Web 保持现有工程
2. 方案B：`Taro` 重构多端前端，Web 并轨
3. 方案C：App 使用 Flutter/React Native，小程序独立开发

推荐方案：A（分阶段）
1. 理由：现有团队与代码以 Vue 为主，迁移学习成本最低。
2. 风险：UI 组件体系需替换，不能直接复用 Element Plus。
3. 控制策略：先抽业务域与 API SDK，再做端侧 UI 重建。

## 3.2 总体架构

1. 服务端：继续使用统一 `api-gateway-java`，增加多端能力接口与协议兼容。
2. 客户端层：
3. `frontend-web`：PC Web（保留并持续演进）
4. `frontend-mobile`（新增，建议 uni-app）：小程序 + App
5. 共享层（新增）：`packages/domain`、`packages/api-sdk`、`packages/constants`

## 4. 产品范围（v3.0）

## 4.1 In Scope

1. 小程序 MVP：登录、聊天、项目列表、任务进度、个人中心、充值入口。
2. App MVP：与小程序同范围，补充系统能力（文件下载、分享、推送预留）。
3. 后端多端兼容：流式协议兼容、设备标识、版本控制、上传策略。
4. 全链路埋点与观测：按端上报关键行为。

## 4.2 Out of Scope

1. iPad/平板专用布局优化。
2. 离线编辑与本地数据库。
3. 多组织协作与企业权限系统。

## 5. 关键需求拆解

## 5.1 账号与鉴权

1. 保持现有 token 机制不变。
2. 新增“端信息上报”头：`X-Client-Platform`、`X-App-Version`、`X-Device-Id`。
3. 小程序新增登录方式（如 `code` 登录）可作为 v3.1 扩展；v3.0 先用手机号/验证码登录打通。

## 5.2 聊天能力跨端

1. Web：继续使用 SSE。
2. 小程序/App：定义降级策略：
3. 首选：平台支持流式则走 chunk/流式通道。
4. 兜底：非流式接口 + 轮询消息结果（保证功能可用）。
5. 前端消息状态统一：`pending/streaming/done/error`。

## 5.3 项目与任务能力

1. 项目列表、项目详情、任务进度、任务结果保持统一 API。
2. 预览能力：
3. 小程序：优先提供“外链打开/复制链接”与截图预览。
4. App：支持内嵌 WebView 预览。

## 5.4 账户与充值

1. 个人中心：基础信息、余额/积分、账单记录。
2. 充值：套餐选择 -> 下单 -> 支付 -> 回调入账 -> 余额刷新。
3. 支付渠道按端适配（小程序支付、App 支付、Web 支付）。

## 6. 迭代规划（可执行 Phase）

## 6.1 Phase 0：架构准备（2 周）

前端任务
1. 设计并落地共享包：`api-sdk/domain/constants`。
2. 抽离现有 `frontend-web` 的领域逻辑（chat/task/billing/auth store 能力接口化）。
3. 定义跨端设计规范：页面信息架构、状态机、埋点字段。

后端任务
1. 增加客户端标识解析与日志打点。
2. 输出 API 多端兼容规范（字段、错误码、时区、分页）。
3. 增加版本控制策略（最低版本、灰度开关预留）。

验收标准
1. 共享 SDK 可被 Web 工程实际消费。
2. 后端日志可区分 Web/小程序/App 请求来源。

## 6.2 Phase 1：小程序 MVP（3 周）

前端任务
1. 新建 `frontend-mobile`（uni-app），完成基础工程与路由。
2. 实现登录、首页、项目列表、聊天、任务进度、个人中心页面。
3. 接入共享 API SDK。
4. 聊天实现“流式优先 + 轮询兜底”。
5. 上线充值入口与订单状态查询页面。

后端任务
1. 补充多端字段校验与统一响应兼容。
2. 提供聊天非流式兜底接口（若已有可直接复用）。
3. 完成充值订单与回调链路稳定化（幂等、审计）。

验收标准
1. 小程序端核心链路可跑通。
2. 聊天在不支持流式场景下可用且无阻断。

## 6.3 Phase 2：App MVP（3 周）

前端任务
1. 在 `frontend-mobile` 打包 App（Android 优先，iOS 次阶段）。
2. 增加 App 特性：下载管理、WebView 预览、分享能力。
3. 适配深色模式、刘海屏安全区、后台恢复状态。

后端任务
1. 增加 App 版本检查接口（可选 `/api/app/version`）。
2. 预留推送消息接口（任务完成通知）。

验收标准
1. App 端关键链路与小程序一致。
2. 下载与预览体验可用。

## 6.4 Phase 3：统一体验与稳定性（2 周）

前端任务
1. 统一三端文案、状态反馈与错误提示。
2. 完成关键页面性能优化（首屏、列表、消息渲染）。
3. 埋点补齐并对接看板。

后端任务
1. 完善限流与熔断策略（按端、按用户）。
2. 完善慢查询与错误码监控。
3. 提供端维度运营数据导出接口（可选）。

验收标准
1. 三端核心指标稳定达标。
2. 无 P0/P1 线上阻断问题。

## 7. API 与数据改造要求

## 7.1 API 规范增强

1. 统一返回结构保持不变：`{ code, message, data }`。
2. 错误码标准化并补充多端解释文档。
3. 分页接口统一：`pageNo/pageSize/total/list`。
4. 时间字段统一 ISO-8601，客户端本地化展示。

## 7.2 新增/增强接口建议

1. `GET /api/user/profile`（个人中心）
2. `POST /api/billing/recharge/orders`（创建充值订单）
3. `GET /api/billing/recharge/orders/{orderId}`（查询订单）
4. `POST /api/billing/recharge/callback`（支付回调）
5. `DELETE /api/chat/sessions/{sessionId}`（会话删除）
6. `GET /api/app/version`（App 版本检查，可选）

## 8. 非功能需求

1. 性能：
2. 小程序首屏可交互时间 <= 2.5s（Wi-Fi 基线）
3. 聊天首响应可感知时间 <= 1s（有等待态）
4. 安全：
5. 支付回调验签 + 幂等 + 审计日志
6. Token 风险控制与设备指纹风控预留
7. 稳定性：
8. 核心接口可用性 >= 99.9%
9. 全链路 traceId 可追踪

## 9. 测试与联调计划

1. 单端测试：Web/小程序/App 各自功能回归。
2. 跨端一致性测试：同一账号在多端数据一致。
3. 聊天链路测试：流式、降级、超时、重试。
4. 充值链路测试：下单、支付、回调、重复回调、账实一致。
5. 主流程回归：登录、聊天、确认栈、生成、预览、下载。

## 10. 指标与验收口径（DoD）

1. 三端均可完成核心业务闭环。
2. 小程序/App 与 Web 的核心 API 复用率 >= 85%。
3. 聊天失败可恢复率 >= 95%。
4. 充值到账准确率 100%，重复回调不重复入账。
5. 多端上线后 2 周内无 P0 事故。

## 11. 风险与应对

1. 风险：小程序流式能力差异导致体验不一致。
2. 应对：协议降级与状态机统一，先保障可用再优化体验。
3. 风险：UI 组件迁移成本高。
4. 应对：先抽业务层，UI 分端重建；避免强行复用 Web 组件。
5. 风险：支付链路复杂度上升。
6. 应对：MVP 先打通单渠道，强化幂等与审计，再扩展渠道。

## 12. 发布策略

1. 发布顺序：Web（稳定）-> 小程序灰度 -> App 内测 -> 全量。
2. 开关策略：按端开关、按用户白名单、按功能开关。
3. 回滚策略：关闭端入口，不回退后端核心主链路。

## 13. Token 计费统计专项需求（新增）

## 13.1 目标

1. 建立统一 token 统计口径，为后续“按 token/混合计费”提供准确数据基础。
2. 按场景拆分统计：`chat`、`codegen`、`paper` 三类独立核算。
3. 支持按用户、任务、项目、端类型追溯成本与消耗。

## 13.2 统计范围与口径

1. `chat` 场景：对话请求与回复 token（含流式与非流式）。
2. `codegen` 场景：结构生成、文件生成、SQL 生成等步骤 token。
3. `paper` 场景：选题澄清、检索、大纲生成、质检 token。
4. 统计字段统一：
5. `token_input`、`token_output`、`token_total`。
6. `model`、`scene`、`task_id`、`project_id`、`user_id`、`client_platform`、`created_at`。

## 13.3 数据模型（新增）

新增表：`token_usage_records`

1. `id` BIGINT PK
2. `user_id` BIGINT NOT NULL
3. `project_id` VARCHAR(64) NULL
4. `task_id` VARCHAR(64) NULL
5. `scene` VARCHAR(32) NOT NULL  // chat/codegen/paper
6. `sub_scene` VARCHAR(64) NULL  // 如 codegen_frontend、outline_generate
7. `model` VARCHAR(64) NOT NULL
8. `token_input` INT NOT NULL DEFAULT 0
9. `token_output` INT NOT NULL DEFAULT 0
10. `token_total` INT NOT NULL DEFAULT 0
11. `client_platform` VARCHAR(32) NULL // web/miniprogram/app
12. `request_id` VARCHAR(64) NULL
13. `created_at` DATETIME NOT NULL

索引建议：
1. `idx_user_scene_time(user_id, scene, created_at)`
2. `idx_task_scene(task_id, scene, created_at)`
3. `idx_project_scene(project_id, scene, created_at)`

## 13.4 后端改造需求

1. 在 `ChatService` 的流式与非流式分支落 token 统计记录。
2. 在 `ModelService` 中 codegen/paper 相关调用统一埋点落库。
3. 保留并复用现有 `PromptHistory` token 字段，新增落地到 `token_usage_records`，避免重复计算。
4. 增加聚合查询接口：
5. `GET /api/billing/token/summary?from=...&to=...&scene=...`
6. `GET /api/billing/token/details?scene=...&pageNo=...&pageSize=...`

## 13.5 前端展示需求

1. 个人中心新增“Token 用量”卡片（总量 + 分场景占比）。
2. 账单/用量页支持按场景筛选：`chat`、`codegen`、`paper`。
3. 支持时间维度查询（日/周/月）。

## 13.6 与计费策略关系

1. v3.0 默认不直接切换为纯 token 扣费。
2. 先上线“统计不结算”模式，持续观测 2~4 周。
3. 后续可切换为“固定扣费 + token 超额浮动扣费”混合策略（另立计费变更需求）。

## 13.7 验收标准

1. 三类场景 token 统计覆盖率 >= 99%。
2. `PromptHistory` 与 `token_usage_records` 聚合误差 <= 1%。
3. 可按用户/任务/项目/场景追溯 token 消耗明细。
4. 前端能展示分场景 token 用量与趋势。

## 14. Phase 1 执行基线（已落地）

## 14.1 frontend-mobile 工程现状

1. 已新增 `frontend-mobile` 工程，覆盖页面：
2. 登录、首页、项目列表、聊天、任务进度、个人中心、积分充值。
3. 已接入共享层：
4. `@smartark/api-sdk`、`@smartark/domain`、`@smartark/constants`。
5. 聊天链路支持“流式优先 + 轮询兜底”状态收敛。
6. 充值链路支持“下单后自动轮询订单状态并到账刷新”。

## 14.2 可稳定构建版本锁定

1. `@dcloudio/uni-app`: `3.0.0-5000420260318001`
2. `@dcloudio/vite-plugin-uni`: `3.0.0-5000420260318001`
3. `@dcloudio/types`: `3.4.28`
4. `vite`: `5.2.8`
5. `@vitejs/plugin-vue`: `5.2.x`
6. `vue`: `3.4.21`
7. `pinia`: `2.1.7`

## 14.3 构建状态

1. `frontend-mobile` 已通过：
2. `npm run type-check`
3. `npm run build:h5`

## 14.4 运维约束

1. 移动端依赖升级必须按“先锁版本 -> 安装 -> 类型检查 -> H5 构建”流程执行。
2. 详细规则见：
3. `docs/移动端依赖升级准则.md`
