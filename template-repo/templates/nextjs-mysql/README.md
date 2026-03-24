# __DISPLAY_NAME__

Next.js App Router + React + Prisma + MySQL 模板。

## 快速开始

1. 复制环境变量

```bash
cp .env.example .env
```

1. 启动数据库

```bash
docker compose up -d
```

1. 安装依赖并初始化 Prisma

```bash
npm install
npm run prisma:generate
npm run db:push
npm run db:seed
npm run dev
```

## 默认地址

- Web: `http://localhost:3000`
- MySQL: `localhost:3306`
