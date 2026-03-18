#!/bin/bash
export DB_HOST=127.0.0.1
export DB_PORT=3306
export DB_NAME=smartark
export DB_USER=smartark
export DB_PASSWORD=smartark
export REDIS_HOST=127.0.0.1
export REDIS_PORT=6379
export JWT_SECRET=dev-local-secret
export MODEL_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1/
export MODEL_API_KEY=sk-62127047df334f8d9fab4483bb4a1c3e
export CHAT_MODEL=qwen-plus
export CODE_MODEL=qwen-plus

java -jar target/api-gateway-0.0.1-SNAPSHOT.jar