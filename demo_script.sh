#!/bin/bash
set -e

echo "=== Smart Code Ark End-to-End Demo Script ==="

echo "1. Registering user 'demo_user'..."
curl -s -X POST http://localhost:8080/api/auth/register -H 'Content-Type: application/json' -d '{"username":"demo_user","password":"p1"}'

echo -e "\n\n2. Logging in..."
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"username":"demo_user","password":"p1"}' | grep -o '"token":"[^"]*' | cut -d'"' -f4)
echo "Token: ${TOKEN:0:20}..."

echo -e "\n\n3. Setting initial quota for demo_user to 100 via DB..."
docker compose -f /Users/fu/FuYao/trace/smart_code_ark/docker-compose.yml exec -T mysql mysql -usmartark -psmartark -h 127.0.0.1 --protocol=tcp smartark -e "UPDATE users SET quota = 100 WHERE username = 'demo_user';"

echo -e "\n\n4. Checking billing balance..."
curl -s http://localhost:8080/api/billing/balance -H "Authorization: Bearer $TOKEN"

echo -e "\n\n5. Starting chat session..."
SESSION_ID=$(curl -s -X POST http://localhost:8080/api/chat/start -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"title":"demo app","projectType":"web","description":"demo desc"}' | grep -o '"sessionId":"[^"]*' | cut -d'"' -f4)
echo "Session ID: $SESSION_ID"

echo -e "\n\n6. Sending chat message..."
curl -s -X POST http://localhost:8080/api/chat -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "{\"sessionId\":\"$SESSION_ID\",\"message\":\"我要做一个二手平台\"}"

echo -e "\n\n7. Confirming project..."
PROJECT_ID=$(curl -s -X POST http://localhost:8080/api/projects/confirm -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "{\"sessionId\":\"$SESSION_ID\",\"stack\":{\"backend\":\"fastapi\",\"frontend\":\"vue3\",\"db\":\"mysql\"}}" | grep -o '"projectId":"[^"]*' | cut -d'"' -f4)
echo "Project ID: $PROJECT_ID"

echo -e "\n\n8. Generating task (will deduct 10 quota)..."
TASK_ID=$(curl -s -X POST http://localhost:8080/api/generate -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "{\"projectId\":\"$PROJECT_ID\"}" | grep -o '"taskId":"[^"]*' | cut -d'"' -f4)
echo "Task ID: $TASK_ID"

echo -e "\n\n9. Checking billing balance (should be 90)..."
curl -s http://localhost:8080/api/billing/balance -H "Authorization: Bearer $TOKEN"

echo -e "\n\n10. Checking task status..."
curl -s http://localhost:8080/api/task/$TASK_ID/status -H "Authorization: Bearer $TOKEN"

echo -e "\n\n11. Wait for task to finish (about 12 seconds)..."
sleep 12
curl -s http://localhost:8080/api/task/$TASK_ID/status -H "Authorization: Bearer $TOKEN"

echo -e "\n\n12. Getting preview URL..."
curl -s http://localhost:8080/api/task/$TASK_ID/preview -H "Authorization: Bearer $TOKEN"

echo -e "\n\n13. Checking billing records..."
curl -s http://localhost:8080/api/billing/records -H "Authorization: Bearer $TOKEN"

echo -e "\n\n=== Demo Completed ==="
