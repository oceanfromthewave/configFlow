# ConfigFlow 통합 개발 기동 스크립트
# backend(8465) + frontend(5173) + electron을 함께 띄운다.
# 사용법:
#   .\scripts\dev.ps1              # 전체 기동
#   .\scripts\dev.ps1 -NoDesktop   # backend + frontend만 (브라우저 개발)

param(
    [switch]$NoDesktop
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

$BackendPort = 8465
$DevToken    = 'dev-token'

Write-Host "[ConfigFlow] backend 기동 (port=$BackendPort)..." -ForegroundColor Cyan
$backend = Start-Process -PassThru -WorkingDirectory "$root\backend" -FilePath "$root\backend\gradlew.bat" `
    -ArgumentList "bootRun", "--args=--server.port=$BackendPort --configflow.token=$DevToken"

Write-Host "[ConfigFlow] frontend 기동 (port=5173)..." -ForegroundColor Cyan
$frontend = Start-Process -PassThru -WorkingDirectory "$root\frontend" -FilePath "cmd.exe" `
    -ArgumentList "/c", "npm run dev"

# backend health 대기 (최대 90초)
$healthUrl = "http://127.0.0.1:$BackendPort/api/v1/health"
$deadline = (Get-Date).AddSeconds(90)
$up = $false
while ((Get-Date) -lt $deadline) {
    try {
        $r = Invoke-WebRequest -Uri $healthUrl -Headers @{ 'X-ConfigFlow-Token' = $DevToken } -UseBasicParsing -TimeoutSec 2
        if ($r.StatusCode -eq 200) { $up = $true; break }
    } catch { Start-Sleep -Milliseconds 500 }
}
if ($up) { Write-Host "[ConfigFlow] backend UP" -ForegroundColor Green }
else     { Write-Host "[ConfigFlow] backend health 확인 실패 - 로그를 확인하세요" -ForegroundColor Yellow }

if (-not $NoDesktop) {
    Write-Host "[ConfigFlow] electron 기동..." -ForegroundColor Cyan
    $env:CONFIGFLOW_DEV = '1'
    $env:CONFIGFLOW_BACKEND_URL = "http://127.0.0.1:$BackendPort/api/v1"
    $env:CONFIGFLOW_TOKEN = $DevToken
    Start-Process -WorkingDirectory "$root\desktop" -FilePath "cmd.exe" -ArgumentList "/c", "npm run dev"
} else {
    Write-Host "[ConfigFlow] 브라우저 모드: http://localhost:5173" -ForegroundColor Green
}

Write-Host "[ConfigFlow] 종료하려면 각 창을 닫거나 아래 PID를 종료하세요: backend=$($backend.Id) frontend=$($frontend.Id)"
