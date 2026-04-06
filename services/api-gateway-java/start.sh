#!/bin/bash
export DB_HOST=127.0.0.1
export DB_PORT=3306
export DB_NAME=smartark
export DB_USER=smartark
export DB_PASSWORD=smartark
export REDIS_HOST=127.0.0.1
export REDIS_PORT=6379
export JWT_SECRET=dev-local-secret
export MODEL_BASE_URL=${MODEL_BASE_URL:-}
export MODEL_API_KEY=${MODEL_API_KEY:-}
export MODEL_MOCK_ENABLED=${MODEL_MOCK_ENABLED:-true}
export CHAT_MODEL=${CHAT_MODEL:-qwen-plus}
export CODE_MODEL=${CODE_MODEL:-qwen-plus}

java -jar target/api-gateway-0.0.1-SNAPSHOT.jar
