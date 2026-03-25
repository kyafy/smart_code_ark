$ErrorActionPreference = "Stop"

$rootDir = Split-Path -Parent $PSScriptRoot
Set-Location $rootDir

docker compose up -d mysql

Write-Host "start backend:"
Write-Host "  cd backend; python -m venv .venv; .venv\\Scripts\\pip install -r requirements.txt; .venv\\Scripts\\python manage.py migrate; .venv\\Scripts\\python manage.py runserver 0.0.0.0:8000"
Write-Host "start frontend:"
Write-Host "  cd frontend; npm install; npm run dev"
