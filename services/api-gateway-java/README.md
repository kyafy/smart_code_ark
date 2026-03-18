# api-gateway-java

## 数据库初始化
- 默认使用 MySQL 8.0（见 [docker-compose.yml](file:///Users/fu/FuYao/trace/smart_code_ark/docker-compose.yml)）
- 通过 Flyway 自动执行迁移：`src/main/resources/db/migration`

## Redis（短信验证码）
- 本地默认启用 Redis（见 [docker-compose.yml](file:///Users/fu/FuYao/trace/smart_code_ark/docker-compose.yml)）
- `/api/auth/sms/send` 会把验证码写入 Redis（5 分钟过期），并对手机号/IP 做限流与失败次数限制
- `/api/auth/login/sms` 会校验验证码，校验通过后立即删除验证码 key（一次性）

## Repository 与事务边界
- Repository 仅负责单表 CRUD 与简单查询；跨表聚合放在 Service
- 事务以“一个业务动作”为边界：
  - `generate/modify`：创建 tasks + 初始化 task_steps +（后续）计费预校验与落账，必须放在同一事务中
  - 余额扣减与 billing_records 写入必须原子化，避免并发下出现负余额或重复扣费
- 任何外部调用（模型/下游服务/对象存储）不在事务内执行；事务只覆盖本地 DB 写入
