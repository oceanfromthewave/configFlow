# jlink로 백엔드 실행에 필요한 최소 JRE를 installer/jre에 생성한다.
#
# 모듈 목록은 추측이 아니라 실측: bootstrap fat jar를 jarmode=tools로 추출한 뒤
# jdeps --recursive --print-module-deps로 뽑았다(2026-08-04, Spring Boot 3.5.6/JDK 21
# 기준). 의존성이 바뀌면(백엔드 라이브러리 추가/제거) 재실측 필요:
#   java -Djarmode=tools -jar bootstrap-*.jar extract --destination extracted
#   jdeps --multi-release 21 --ignore-missing-deps --recursive --print-module-deps -cp "extracted/lib/*" extracted/bootstrap-*.jar

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$modules = 'java.base,java.compiler,java.desktop,java.instrument,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,jdk.jfr,jdk.unsupported'
$output = "$PSScriptRoot\jre"

if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    throw "JAVA_HOME이 유효한 JDK를 가리켜야 함 (jlink는 대상 JDK의 jmods가 필요)"
}

if (Test-Path $output) { Remove-Item -Recurse -Force $output }

& "$env:JAVA_HOME\bin\jlink.exe" `
    --add-modules $modules `
    --output $output `
    --strip-debug `
    --no-header-files `
    --no-man-pages `
    --compress zip-6
if ($LASTEXITCODE -ne 0) { throw "jlink failed" }

Write-Host "JRE 생성 완료 → $output" -ForegroundColor Green
