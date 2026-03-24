# __DISPLAY_NAME__

FastAPI + Vue 3 + MySQL 模板，适合需要 Python API 能力的后台项目、AI 辅助工具和课程设计项目。

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
cd backend && python -m venv .venv
.venv/Scripts/pip install -r requirements.txt
.venv/Scripts/python -m uvicorn app.main:app --reload --port 8000
cd frontend && npm install && npm run dev
```

## 默认地址

- 前端: `http://localhost:5173`
- 后端: `http://localhost:8000`
- OpenAPI: `http://localhost:8000/docs`
- MySQL: `localhost:3306`
