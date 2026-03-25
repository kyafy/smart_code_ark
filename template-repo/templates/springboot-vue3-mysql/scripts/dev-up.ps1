$ErrorActionPreference = "Stop"

$rootDir = Split-Path -Parent $PSScriptRoot
Set-Location $rootDir

docker compose up -d mysql

Write-Host "start backend:"
Write-Host "  cd backend; mvn spring-boot:run"
Write-Host "start frontend:"
Write-Host "  cd frontend; npm install; npm run dev"
