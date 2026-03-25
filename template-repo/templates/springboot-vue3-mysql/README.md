# __DISPLAY_NAME__

Spring Boot 3 + Vue 3 + MySQL 模板，适合后台管理系统、课程设计、业务中台类项目。

## 目录

```text
.
├─ backend
├─ frontend
├─ docker-compose.yml
└─ .env.example
```

## 快速开始

1. 复制环境变量文件

```bash
cp .env.example .env
```

1. 启动整套容器

```bash
docker compose up --build
```

1. 或使用本地开发方式

```bash
docker compose up -d mysql
cd backend && mvn spring-boot:run
cd frontend && npm install && npm run dev
```

## 默认地址

- 前端: `http://localhost:5173`
- 后端: `http://localhost:8080`
- MySQL: `localhost:3306`
