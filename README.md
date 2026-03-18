# Smart Code Ark (慧码方舟)

Smart Code Ark 是一个基于 AI 的毕业设计项目生成平台，旨在通过对话式交互辅助用户完成从需求梳理、技术选型到代码生成的全过程。

## 项目简介

本项目利用大模型能力，帮助学生快速构建毕业设计项目。用户只需输入简单的毕设题目，系统即可自动进行需求分析、生成 PRD 文档、设计数据库模型，并最终生成完整的前后端代码。

## 技术栈

### 前端 (Frontend)
- **框架**: Vue 3 + TypeScript
- **构建工具**: Vite
- **UI 组件库**: Element Plus
- **样式**: Tailwind CSS
- **状态管理**: Pinia
- **路由**: Vue Router

### 后端 (Backend)
- **核心框架**: Spring Boot 3
- **构建工具**: Maven
- **数据库**: MySQL 8.0
- **ORM**: Spring Data JPA
- **API 文档**: (待集成 Swagger/OpenAPI)
- **AI 集成**: 自研 ModelService 对接大模型

### 基础设施
- **容器化**: Docker & Docker Compose
- **数据库迁移**: Flyway

## 目录结构

```
smart_code_ark/
├── frontend-web/          # 前端项目源码
├── services/
│   └── api-gateway-java/  # 后端 API 网关服务 (Spring Boot)
├── docker-compose.yml     # 容器编排配置
└── scripts/               # 辅助脚本
```

## 快速开始

### 前置要求
- Java 17+
- Node.js 18+
- Docker & Docker Compose
- Maven 3.8+

### 1. 启动基础设施 (MySQL)

```bash
docker-compose up -d
```

### 2. 启动后端服务

```bash
cd services/api-gateway-java
./start.sh
# 或者
mvn spring-boot:run
```
后端服务将运行在 `http://localhost:8080`。

### 3. 启动前端服务

```bash
cd frontend-web
npm install
npm run dev
```
前端服务将运行在 `http://localhost:5173`。

## 功能特性

- **智能对话**: 通过 AI 聊天界面引导用户完善需求。
- **自动化 PRD**: 自动生成包含项目概览、功能列表、页面结构的 PRD 文档。
- **一键生成**: 确认需求后，一键生成完整的前后端代码工程。
- **实时预览**: 在线预览生成的项目效果。
- **源码下载**: 支持下载生成的项目源码包。

## 贡献指南

欢迎提交 Issue 和 Pull Request 来改进本项目。

## 许可证

MIT License
