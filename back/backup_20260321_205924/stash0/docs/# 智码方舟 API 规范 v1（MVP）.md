# 智码方舟 API 规范 v1（MVP）

## 1. 通用约定
- Base URL：`/api`
- 认证方式：`Authorization: Bearer <JWT>`
- Content-Type：`application/json; charset=utf-8`
- 时间格式：ISO8601（UTC）
- 统一响应格式：
```json
{
  "code": 0,
  "message": "ok",
  "data": {}
}
```

## 2. 错误码规范
- `0` 成功
- `1001` 参数校验失败（HTTP 400）
- `1002` 未登录或Token失效（HTTP 401）
- `1003` 权限不足（HTTP 403）
- `1004` 资源不存在（HTTP 404）
- `1005` 幂等冲突/状态冲突（HTTP 409）
- `2001` 积分不足（HTTP 402）
- `3001` 模型服务异常（HTTP 502）
- `3002` 任务执行失败（HTTP 500）
- `3003` 任务超时（HTTP 504)
- `9000` 系统内部错误（HTTP 500）

## 3. 认证接口
### 3.1 POST /auth/register
- 请求：
```json
{ "username": "zhangsan", "password": "******", "phone": "13800000000" }
```
- 响应：
```json
{ "code": 0, "message": "ok", "data": { "userId": 1 } }
```

### 3.2 POST /auth/login
- 请求：
```json
{ "username": "zhangsan", "password": "******" }
```
- 响应：
```json
{ "code": 0, "message": "ok", "data": { "token": "jwt", "userId": 1 } }
```

### 3.3 POST /auth/sms/send
- 请求：
```json
{ "phone": "13800000000", "scene": "login" }
```
- 响应：
```json
{ "code": 0, "message": "ok", "data": { "requestId": "sms_req_001", "expireIn": 300 } }
```

### 3.4 POST /auth/login/sms
- 请求：
```json
{ "phone": "13800000000", "captcha": "123456" }
```
- 响应：
```json
{ "code": 0, "message": "ok", "data": { "token": "jwt", "userId": 1 } }
```

## 4. 项目与对话
### 4.1 POST /chat/start
- 说明：创建对话会话并进入“需求梳理”阶段，并生成 `projectId`（此时不选技术栈）
```json
{ "title": "校园二手交易平台", "projectType": "web" }
```
```json
{ "code": 0, "message": "ok", "data": { "sessionId": "s_001", "stage": "requirement" } }
```

### 4.2 POST /chat
- 说明：多轮对话梳理功能清单与数据库设计
```json
{ "sessionId": "s_001", "message": "我需要订单和支付模块" }
```
```json
{ "code": 0, "message": "ok", "data": { "reply": "建议包含订单状态流转...", "draftModules": ["用户", "商品", "订单"] } }
```

### 4.3 POST /projects/confirm
- 说明：对话完成后确认项目并选择技术栈，更新项目表里面的内容
```json
{
  "sessionId": "s_001",
  "stack": { "backend": "fastapi", "frontend": "vue3", "db": "mysql" }
}
```
```json
{ "code": 0, "message": "ok", "data": { "projectId": "p_001", "status": "draft" } }
```

## 5. 生成与任务
### 5.1 POST /generate
```json
{
  "projectId": "p_001"
}
```
```json
{ "code": 0, "message": "ok", "data": { "taskId": "t_001", "status": "queued" } }
```

### 5.2 GET /task/{taskId}/status
```json
{ "code": 0, "message": "ok", "data": { "status": "running", "progress": 55, "step": "生成后端接口" } }
```

### 5.3 GET /task/{taskId}/download
- 成功返回：ZIP 二进制流（或预签名URL）

### 5.4 GET /task/{taskId}/preview
```json
{ "code": 0, "message": "ok", "data": { "previewUrl": "https://preview.xxx/t_001" } }
```

### 5.5 POST /task/{taskId}/modify
```json
{ "changeInstructions": "订单模块增加待发货状态" }
```
```json
{ "code": 0, "message": "ok", "data": { "taskId": "t_002", "status": "queued" } }
```

## 6. 计费接口（MVP）
### 6.1 GET /billing/balance
### 6.2 GET /billing/records?projectId=&taskId=
- 扣费在 `/generate` 提交前进行预校验，提交后事务落账