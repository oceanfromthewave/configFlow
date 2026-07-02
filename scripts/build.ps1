# ConfigFlow 전체 빌드 스크립트
# backend(jar) → frontend(dist) → desktop(compile) 순서로 빌드하고 실패 시 중단한다.

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

Write-Host "[1/3] backend build..." -ForegroundColor Cyan
Push-Location "$root\backend"
& .\gradlew.bat build
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "backend build failed" }
Pop-Location

Write-Host "[2/3] frontend build..." -ForegroundColor Cyan
Push-Location "$root\frontend"
npm run build
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "frontend build failed" }
Pop-Location

Write-Host "[3/3] desktop build..." -ForegroundColor Cyan
Push-Location "$root\desktop"
npm run build
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "desktop build failed" }
Pop-Location

Write-Host "빌드 완료" -ForegroundColor Green
