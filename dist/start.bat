@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul

REM ============================================================
REM  Reaction Bot 실행 스크립트 (Windows)
REM  - jar 옆에 config.yml이 있으면 자동 머지 (사용자 설정 우선)
REM  - 처음 실행 시 config.yml 템플릿 자동 생성
REM ============================================================

cd /d "%~dp0"

REM Java 확인
where java >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Java가 설치되어 있지 않습니다.
  echo Java 21 이상이 필요합니다. https://adoptium.net/ 에서 설치 후 다시 시도하세요.
  echo.
  pause
  exit /b 1
)

REM Python 확인 (STT/TTS 스크립트 실행용)
where python >nul 2>nul
if errorlevel 1 (
  echo [경고] Python이 설치되어 있지 않습니다.
  echo STT/TTS 기능은 동작하지 않을 수 있습니다.
  echo Python 3.9~3.13 설치: https://www.python.org/
  echo.
)

REM jar 파일 찾기 (배포 번들: reaction-bot.jar / 개발 빌드: build\libs\reaction-bot-*.jar)
set "JAR_FILE="
for %%f in (reaction-bot.jar reaction-bot-*.jar build\libs\reaction-bot-*.jar) do (
  if exist "%%f" set "JAR_FILE=%%f"
)
if "%JAR_FILE%"=="" (
  echo [ERROR] reaction-bot-*.jar 파일을 찾을 수 없습니다.
  echo jar 파일이 이 스크립트와 같은 폴더 또는 build\libs\ 안에 있어야 합니다.
  pause
  exit /b 1
)
echo [INFO] JAR: %JAR_FILE%

REM config.yml 없으면 템플릿 생성
if not exist "config.yml" (
  echo [INFO] config.yml 템플릿 생성 중...
  (
    echo # reaction-bot 사용자 설정. /config UI에서 편집 가능.
    echo # 시크릿 값들을 채워 넣으세요.
    echo reaction-bot:
    echo   anthropic:
    echo     api-key: ""              # https://console.anthropic.com/settings/keys
    echo   character:
    echo     name: "리봇"
    echo     streamer-name: "스트리머"
  ) > config.yml
  echo [INFO] config.yml 생성됨. http://localhost:8080/config 에서 채워 넣으세요.
)

REM 서버 시작 (config.yml 머지)
echo.
echo ========================================
echo  서버 시작 중... (Ctrl+C 로 종료)
echo  설정 페이지:  http://localhost:8080/config
echo  아바타:       http://localhost:8080/avatar
echo ========================================
echo.

REM 기동 5초 뒤 설정 페이지 자동 오픈 (백그라운드)
start "" cmd /c "timeout /t 5 /nobreak >nul && start http://localhost:8080/config"

java -jar "%JAR_FILE%" --spring.config.additional-location=file:./config.yml

endlocal
