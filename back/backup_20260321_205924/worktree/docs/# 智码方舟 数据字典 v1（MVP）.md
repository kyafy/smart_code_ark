# 智码方舟 数据字典 v1（MVP）

## 1. 约定
- 数据库：MySQL 8.0
- 主键：`bigint` 或 `varchar(64)`（业务ID）
- 时间：`datetime`（UTC）
- 软删除：`is_deleted tinyint(1)`（0/1）
- 状态字段统一使用枚举字符串（可读性优先）

## 2. 核心表

## 2.1 users（用户表）
- `id bigint PK`
- `username varchar(64) UNIQUE`
- `password_hash varchar(255)`
- `role varchar(32)`（user/admin）
- `balance decimal(10,2)`（账户余额）
- `quota int`（剩余可生成次数/积分）
- `created_at datetime`
- `updated_at datetime`

## 2.2 chat_sessions（会话表）
- `id varchar(64) PK`（sessionId）
- `user_id bigint`
- `project_id varchar(64)`
- `title varchar(128)`
- `status varchar(32)`（active/closed/expired）
- `created_at datetime`
- `updated_at datetime`

## 2.3 chat_messages（会话消息表）
- `id bigint PK`
- `session_id varchar(64)`
- `speaker varchar(16)`（user/assistant/system）
- `message text`
- `token_used int`
- `created_at datetime`
- 索引：`idx_session_created(session_id, created_at)`

## 2.4 projects（项目表）
- `id varchar(64) PK`
- `user_id bigint`
- `title varchar(128)`
- `description varchar(1000)`（项目描述）
- `project_type varchar(32)`（web/h5/miniprogram/app）
- `stack_backend varchar(32)`
- `stack_frontend varchar(32)`
- `stack_db varchar(32)`
- `status varchar(32)`（draft/generating/ready/failed）
- `created_at datetime`
- `updated_at datetime`

## 2.5 project_specs（结构化需求表）
- `id bigint PK`
- `project_id varchar(64)`
- `version int`
- `requirement_json json`
- `domain_json json`
- `api_contract_json json`
- `created_at datetime`
- 唯一：`uk_project_version(project_id, version)`

## 2.6 tasks（任务表）
- `id varchar(64) PK`（taskId）
- `project_id varchar(64)`
- `user_id bigint`
- `task_type varchar(32)`（generate/modify）
- `status varchar(32)`（queued/running/finished/failed/timeout）
- `progress int`（0-100）
- `current_step varchar(64)`
- `error_code varchar(16)`
- `error_message varchar(255)`
- `result_url varchar(512)`
- `created_at datetime`
- `updated_at datetime`

## 2.7 task_steps（任务执行节点表）
- `id bigint PK`
- `task_id varchar(64)`
- `step_code varchar(64)`（requirement_analyze/codegen_backend/codegen_frontend/sql_generate/package）
- `step_name varchar(128)`
- `step_order int`（执行顺序，从1开始）
- `status varchar(32)`（pending/running/success/failed/skipped）
- `progress int`（0-100）
- `started_at datetime`
- `finished_at datetime`
- `error_code varchar(16)`
- `error_message varchar(255)`
- `retry_count int`
- `created_at datetime`
- `updated_at datetime`
- 索引：`idx_task_step_order(task_id, step_order)`
- 唯一：`uk_task_step(task_id, step_code)`

## 2.8 task_logs（任务日志表）
- `id bigint PK`
- `task_id varchar(64)`
- `level varchar(16)`（info/warn/error）
- `content text`
- `created_at datetime`
- 索引：`idx_task_created(task_id, created_at)`

## 2.9 artifacts（交付物表）
- `id bigint PK`
- `task_id varchar(64)`
- `project_id varchar(64)`
- `artifact_type varchar(32)`（zip/sql/openapi/readme/deploy）
- `storage_url varchar(512)`
- `size_bytes bigint`
- `created_at datetime`

## 2.10 billing_records（计费流水表）
- `id bigint PK`
- `user_id bigint`
- `project_id varchar(64)`
- `task_id varchar(64)`
- `change_amount decimal(10,2)`（负数扣费，正数充值）
- `currency varchar(8)`（CNY）
- `reason varchar(64)`（generate/modify/recharge）
- `balance_after decimal(10,2)`
- `created_at datetime`
- 索引：`idx_user_created(user_id, created_at)`

## 2.11 payment_orders（支付订单表，MVP可选）
- `id varchar(64) PK`
- `user_id bigint`
- `provider varchar(32)`（wechat/alipay）
- `amount decimal(10,2)`
- `status varchar(32)`（pending/success/failed/closed）
- `provider_trade_no varchar(128)`
- `created_at datetime`
- `updated_at datetime`

## 2.12 prompt_templates（Prompt模板表）
- `id bigint PK`
- `template_key varchar(64) UNIQUE`（如 requirement_structuring_v1）
- `name varchar(128)`
- `scene varchar(64)`（requirement/domain/codegen/modify）
- `description varchar(512)`
- `status varchar(32)`（active/inactive）
- `default_version_no int`
- `cache_enabled tinyint(1)`（0/1）
- `cache_ttl_seconds int`
- `created_by bigint`
- `created_at datetime`
- `updated_at datetime`

## 2.13 prompt_versions（Prompt版本表）
- `id bigint PK`
- `template_id bigint`
- `version_no int`
- `system_prompt text`
- `user_prompt text`
- `output_schema_json json`
- `model varchar(64)`
- `temperature decimal(3,2)`
- `top_p decimal(3,2)`
- `status varchar(32)`（draft/published/deprecated）
- `published_by bigint`
- `published_at datetime`
- `created_at datetime`
- 唯一：`uk_template_version(template_id, version_no)`

## 2.14 prompt_history（Prompt执行历史表）
- `id bigint PK`
- `task_id varchar(64)`
- `project_id varchar(64)`
- `template_key varchar(64)`
- `version_no int`
- `model varchar(64)`
- `request_hash varchar(128)`
- `input_json json`
- `output_json json`
- `token_input int`
- `token_output int`
- `latency_ms int`
- `status varchar(32)`（success/failed/timeout）
- `error_code varchar(16)`
- `error_message varchar(255)`
- `created_at datetime`
- 索引：`idx_task_template(task_id, template_key)`
- 索引：`idx_project_created(project_id, created_at)`

## 2.15 prompt_cache（Prompt缓存表）
- `id bigint PK`
- `cache_key varchar(128) UNIQUE`
- `template_key varchar(64)`
- `version_no int`
- `model varchar(64)`
- `request_hash varchar(128)`
- `response_json json`
- `hit_count int`
- `expires_at datetime`
- `created_at datetime`
- `updated_at datetime`
- 索引：`idx_template_version(template_key, version_no)`