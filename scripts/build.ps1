# ConfigFlow 전체 빌드 스크립트
# backend(jar) → frontend(dist) → desktop(compile) 순서로 빌드하고 실패 시 중단한다.

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

# JAVA_HOME이 유효하지 않으면 설치된 JDK를 탐색해 재지정 (dev.ps1과 동일한 보정)
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $candidates = @(
        "$env:USERPROFILE\.jdks\ms-21.0.11",
        "$env:USERPROFILE\.jdks\corretto-21.0.11"
    ) + (Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName) `
      + (Get-ChildItem 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -ExpandProperty FullName)
    foreach ($c in $candidates) {
        if (Test-Path "$c\bin\java.exe") { $env:JAVA_HOME = $c; break }
    }
    Write-Host "JAVA_HOME 보정 → $env:JAVA_HOME" -ForegroundColor Yellow
}

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
