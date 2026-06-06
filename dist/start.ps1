# Reaction Bot 실행 스크립트 (PowerShell)
# - jar 옆에 config.yml이 있으면 자동 머지 (사용자 설정 우선)
# - 처음 실행 시 config.yml 템플릿 자동 생성

Set-Location $PSScriptRoot
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# Java 확인
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Host "[ERROR] Java가 설치되어 있지 않습니다." -ForegroundColor Red
    Write-Host "Java 21 이상이 필요합니다. https://adoptium.net/"
    Read-Host "Enter to exit"
    exit 1
}

# Python 확인
if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
    Write-Host "[경고] Python이 설치되어 있지 않습니다. STT/TTS 동작 불가." -ForegroundColor Yellow
    Write-Host "https://www.python.org/"
    Write-Host ""
}

# jar 찾기 (배포 번들: reaction-bot.jar / 개발 빌드: build\libs\reaction-bot-*.jar)
$jar = Get-ChildItem -Path . -Filter "reaction-bot.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $jar) {
    $jar = Get-ChildItem -Path . -Filter "reaction-bot-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
}
if (-not $jar) {
    $jar = Get-ChildItem -Path "build\libs" -Filter "reaction-bot-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
}
if (-not $jar) {
    Write-Host "[ERROR] reaction-bot-*.jar 파일을 찾을 수 없습니다." -ForegroundColor Red
    Read-Host "Enter to exit"
    exit 1
}
Write-Host "[INFO] JAR: $($jar.FullName)" -ForegroundColor Cyan

# config.yml 템플릿
if (-not (Test-Path "config.yml")) {
    Write-Host "[INFO] config.yml 템플릿 생성 중..." -ForegroundColor Cyan
    @"
# reaction-bot 사용자 설정. /config UI에서 편집 가능.
reaction-bot:
  anthropic:
    api-key: ""              # https://console.anthropic.com/settings/keys
  character:
    name: "리봇"
    streamer-name: "스트리머"
"@ | Out-File -FilePath "config.yml" -Encoding utf8
    Write-Host "[INFO] config.yml 생성됨." -ForegroundColor Cyan
}

Write-Host ""
Write-Host "========================================"
Write-Host "  서버 시작 중... (Ctrl+C 로 종료)"
Write-Host "  설정 페이지:  http://localhost:8080/config"
Write-Host "  아바타:       http://localhost:8080/avatar"
Write-Host "========================================"
Write-Host ""

# 5초 뒤 브라우저 자동 오픈
Start-Job -ScriptBlock { Start-Sleep 5; Start-Process "http://localhost:8080/config" } | Out-Null

& java -jar $jar.FullName --spring.config.additional-location=file:./config.yml
