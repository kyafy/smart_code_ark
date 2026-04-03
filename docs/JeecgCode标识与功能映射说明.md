# JeecgCode 标识与功能映射说明

本文档用于明确 `jeecg.code` 的语义、组件边界、前端兜底策略，以及当前环境下可见的 code 映射清单。

---

## 1. 结论先行

- `jeecg.code` 不是平台模板 `templateId`，而是 Jeecg 侧用于定位“代码生成对象”的业务标识。
- `templateId` 是 smart_code_ark 体系内模板标识；`jeecg.code` 是 Jeecg 体系内生成对象标识，两者不等价。
- 任务链路里应优先使用显式 `jeecg.code`，避免回退到 `templateId` 导致 `tableConfig is null`。

---

## 2. 组件功能边界

### 2.1 api-gateway-java
- 任务编排、参数校验、调用下游。
- `jeecg.code` 从任务参数注入并透传到 Jeecg 调用上下文。

### 2.2 jeecg-sidecar
- 协议适配、登录鉴权、请求重试与日志输出。
- 不负责在线表单建模，不负责业务对象维护。

### 2.3 JeecgBoot
- 仅承担代码生成执行能力。
- 不承担在线表单配置生成流程（本项目约束）。

### 2.4 frontend-web
- 负责让用户选择 `jeecg.code` 并显式提交到任务参数。
- 可保留本地兜底 code 清单，但应支持后端动态下发覆盖。

---

## 3. `jeecg.code` 在链路中的作用

- 作用：定位 Jeecg 端“要生成的目标对象”。
- 非作用：不是“生成内容 id”，也不是平台内部模板 id。
- 若 `jeecg.code` 缺失，当前链路可能回退到 `templateId`，但这通常无法命中 Jeecg 对象，导致失败。

---

## 4. 前端“全量预置 + 动态覆盖”建议

### 4.1 推荐策略
- 前端保留一份全量预置清单作为兜底。
- 进入 `jeecg_rule` 模式时，优先请求后端 code 列表接口；成功则覆盖本地清单，失败则退回本地清单。

### 4.2 预置数据建议结构
- `code`: Jeecg 标识（必填）
- `label`: 展示名（建议）
- `featureTags`: 功能标签（建议）
- `enabled`: 是否可选（建议）

### 4.3 提交流程
- 用户必须显式选择 `jeecg.code`。
- 提交 `POST /api/generate` 时写入 `options.jeecgCode`。

---

## 5. 当前环境可见的 code 映射（示例）

数据来源：`jeecg-boot.onl_cgform_head` + `onl_cgform_field` 联表统计。

| code(table_name) | id | table_txt | table_type | is_db_synch | field_count | 说明 |
|---|---|---|---:|---|---:|---|
| test_demo | d35109c3632c4952a19ecc094943dd71 | 测试DEMO | 1 | Y | 19 | 单表示例，适合联调 |
| test_order_main | 56870166aba54ebfacb20ba6c770bd73 | 测试订单主表 | 2 | Y | 9 | 主子表主表 |
| test_order_product | deea5a8ec619460c9245ba85dbc59e80 | 测试订单商品 | 3 | Y | 11 | 子表 |
| test_order_customer | 41de7884bf9a42b7a2c5918f9f765dff | 测试订单客户 | 3 | Y | 12 | 子表 |
| test_enhance_select | 3d447fa919b64f6883a834036c14aa67 | 测试增强组件 | 1 | Y | 6 | 单表 |
| test_shoptype_tree | 997ee931515a4620bc30a9c1246429a9 | 测试树结构 | 1 | Y | 10 | 树表 |
| test_note | 05a3a30dada7411c9109306aa4117068 | 测试通知 | 1 | Y | 16 | 单表 |

说明：
- 同名 `code` 可能存在 `$1` 后缀变体（通常为复制版本或派生版本），默认不建议优先选用。
- 业务功能能力最终由 Jeecg 内部对象配置决定，字段数仅作参考。

---

## 6. 错误语义速查

- `401 / token为空 / 未授权`：
  - sidecar 登录或上游地址配置错误。
- `status=200, ok=false, tableConfig is null`：
  - `jeecg.code` 未命中 Jeecg 对象，或对象不可用于当前生成链路。

---

## 7. 落地清单（可执行）

- 前端增加 `jeecg.code` 选择器（必选）。
- 前端内置兜底清单（本文第 5 节）。
- 后端增加 code-options 接口（可选，但推荐）。
- 任务创建时若 `codegenEngine=jeecg_rule` 且 `jeecg.code` 为空，直接拒绝并返回可读错误。
