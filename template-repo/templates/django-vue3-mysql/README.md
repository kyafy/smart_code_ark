# __DISPLAY_NAME__

Starter template for a separated Django backend and Vue 3 frontend with MySQL.

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
.venv/Scripts/python manage.py migrate
.venv/Scripts/python manage.py runserver 0.0.0.0:8000
cd frontend && npm install && npm run dev
```

## Default URLs

- Frontend: `http://localhost:5173`
- Backend API: `http://localhost:8000`
- MySQL: `localhost:3306`
