
# Smart Code Ark PRD

更新时间：2026-03-20

说明：本文档基于当前代码实现整理，覆盖 `frontend-web` 与 `services/api-gateway-java`。

## 1. 项目定位

Smart Code Ark 是一个 AI 驱动的“需求到产物”生成平台，当前已实现两条核心链路：

1. 软件项目生成链路
2. 论文提纲生成链路

其中软件项目生成链路支持用户通过对话沉淀需求、确认技术栈、发起异步生成任务，并查看进度、预览和下载产物。

## 2. 目标用户

1. 需要快速搭建 Web/H5/小程序/App 原型的产品或开发人员
2. 需要从自然语言需求快速整理 PRD、技术栈和代码骨架的团队
3. 需要生成论文选题澄清、文献检索、论文大纲的学生或研究人员

## 3. 当前版本核心能力

### 3.1 用户与认证

1. 用户注册
2. 用户密码登录
3. 短信验证码发送与验证码登录
4. JWT 鉴权访问业务接口

### 3.2 软件项目生成

1. 新建需求会话
2. 基于 SSE 的 AI 需求对话
3. 从对话沉淀项目标题、描述、项目类型
4. 选择技术栈并确认项目
5. 创建项目规格快照 `project_specs`
6. 发起生成任务 `generate`
7. 异步执行多步骤 Agent 流程
8. 查看任务状态、日志、预览地址
9. 下载打包产物 ZIP
10. 失败后从指定步骤重试
11. 已运行任务取消

### 3.3 论文提纲生成

1. 输入论文主题、学科、学历层次、方法偏好
2. 主题澄清
3. 检索 Semantic Scholar 文献
4. 生成论文提纲
5. 生成提纲质量检查报告
6. 查询提纲结果

### 3.4 计费与额度

1. 查询余额与配额
2. 查询计费记录
3. 代码生成任务创建时扣减 quota
4. 当前实现中默认单次代码生成/修改任务扣减 10 quota

### 3.5 开发与联调支持

1. `.env.example` 提供本地环境变量模板
2. `scripts/dev-up.sh` 支持一键启动 MySQL、Redis、后端、前端
3. `scripts/dev-down.sh` 支持停止本地开发环境
4. `scripts/up.sh` 支持仅启动 Docker Compose 依赖
5. `scripts/db-reset.sh` 支持重建数据库容器并初始化 MySQL
6. `scripts/e2e_smoke.py` 提供从注册到生成下载的端到端冒烟验证

## 4. 典型业务流程

### 4.1 软件项目生成

1. 用户登录后进入项目列表页
2. 在“新建项目”页填写标题、描述、项目类型
3. 系统创建聊天会话 `chat_session`
4. 用户与 AI 通过 SSE 对话补充需求
5. 用户确认技术栈后提交 `projects/confirm`
6. 系统创建 `projects` 与 `project_specs` 版本 1
7. 用户发起 `/api/generate`
8. 系统创建 `tasks`、`task_steps` 并异步执行
9. 用户在进度页查看步骤、日志、状态
10. 完成后查看预览页并下载 ZIP

### 4.2 论文提纲生成

1. 用户提交论文主题信息
2. 系统创建 `paper_outline` 类型任务
3. 任务按“主题澄清 -> 文献检索 -> 提纲生成 -> 质量检查”执行
4. 结果落库到 `paper_topic_session`、`paper_sources`、`paper_outline_versions`
5. 用户通过 `/api/paper/outline/{taskId}` 查看结果

## 5. 页面与前端功能范围

当前前端路由已实现以下页面：

1. `/login` 登录页
2. `/register` 注册页
3. `/projects` 项目列表页
4. `/projects/new` 新建项目页
5. `/chat/:sessionId` 需求对话页
6. `/projects/:projectId` 项目详情页
7. `/projects/:projectId/stack` 技术栈确认页
8. `/tasks/:taskId/progress` 任务进度页
9. `/tasks/:taskId/result` 任务结果页
10. `/preview/:taskId` 预览页

## 6. 当前版本边界与约束

1. 项目生成的核心产物仍以代码骨架、文件内容、ZIP 打包为主，不是完整 DevOps 平台
2. 模型服务未配置时，可通过 `MODEL_MOCK_ENABLED` 走 mock 逻辑
3. 论文文献来源当前接入 Semantic Scholar
4. 系统没有独立权限模型，默认角色为 `user`
5. 业务鉴权基于 JWT + `AuthInterceptor`
6. 当前任务预览地址写死为 `http://localhost:5173/preview/{taskId}`
