# __DISPLAY_NAME__

Starter template for a separated FastAPI backend and Next.js frontend with MySQL.

## Structure

```text
.
|-- backend
|-- frontend
|-- docker-compose.yml
`-- .env.example
```

## Quick Start

1. Copy the environment file.

```bash
cp .env.example .env
```

2. Start all services with Docker.

```bash
docker compose up --build
```

3. Or run the stack locally.

```bash
docker compose up -d mysql
cd backend && python -m venv .venv
.venv/Scripts/pip install -r requirements.txt
.venv/Scripts/python -m uvicorn app.main:app --reload --port 8000
cd frontend && npm install && npm run dev
```

## Default URLs

- Frontend: `http://localhost:3000`
- Backend API: `http://localhost:8000`
- OpenAPI: `http://localhost:8000/docs`
- MySQL: `localhost:3306`
