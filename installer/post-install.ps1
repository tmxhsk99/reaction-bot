# Reaction Bot 인스톨러 post-install 스크립트
# Inno Setup이 [Run] 단계에서 호출.
#
# 처리:
#   1. Java 21+ 감지 → 없으면 Eclipse Adoptium JDK 21 silent install
#   2. Python 3 감지 → 없으면 python.org 공식 인스톨러 silent install
#   3. pip 패키지 자동 설치 (numpy, sounddevice, faster-whisper, ...)
#
# 로그는 %APPDATA%\ReactionBot\install.log 에 기록.

$ErrorActionPreference = "Continue"
$LogDir = Join-Path $env:APPDATA "ReactionBot"
$LogFile = Join-Path $LogDir "install.log"
New-Item -ItemType Directory -Path $LogDir -Force | Out-Null

function Log {
    param([string]$msg)
    $line = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $msg
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
}

function Test-Java21 {
    try {
        $output = & java -version 2>&1 | Out-String
        if ($output -match 'version "(\d+)') {
            $major = [int]$Matches[1]
            return $major -ge 21
        }
    } catch {}
    return $false
}

function Test-Python {
    try {
        $output = & python --version 2>&1 | Out-String
        return $output -match 'Python 3\.'
    } catch {}
    return $false
}

function Install-Java21 {
    Log "Java 21 다운로드 시작..."
    $url = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.5%2B11/OpenJDK21U-jdk_x64_windows_hotspot_21.0.5_11.msi"
    $msi = Join-Path $env:TEMP "adoptium-21.msi"
    try {
        Invoke-WebRequest -Uri $url -OutFile $msi -UseBasicParsing
        Log "Java MSI 다운로드 완료: $msi"
        $args = "/i `"$msi`" /quiet ADDLOCAL=FeatureMain,FeatureEnvironment,FeatureJarFileRunWith,FeatureJavaHome"
        $proc = Start-Process -FilePath "msiexec.exe" -ArgumentList $args -Wait -PassThru
        Log "Java 설치 exit code: $($proc.ExitCode)"
        Remove-Item $msi -Force -ErrorAction SilentlyContinue
    } catch {
        Log "Java 설치 실패: $_"
    }
}

function Install-Python {
    Log "Python 다운로드 시작..."
    $url = "https://www.python.org/ftp/python/3.12.7/python-3.12.7-amd64.exe"
    $installer = Join-Path $env:TEMP "python-installer.exe"
    try {
        Invoke-WebRequest -Uri $url -OutFile $installer -UseBasicParsing
        Log "Python 인스톨러 다운로드 완료: $installer"
        $args = "/quiet InstallAllUsers=0 PrependPath=1 Include_test=0 Include_doc=0 Include_launcher=1"
        $proc = Start-Process -FilePath $installer -ArgumentList $args -Wait -PassThru
        Log "Python 설치 exit code: $($proc.ExitCode)"
        Remove-Item $installer -Force -ErrorAction SilentlyContinue
    } catch {
        Log "Python 설치 실패: $_"
    }
}

function Install-PipDeps {
    Log "pip 의존성 설치 시작..."
    # PATH 갱신 (방금 설치한 Python 인식)
    $env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")
    $deps = @("numpy", "sounddevice", "requests", "faster-whisper", "webrtcvad-wheels", "edge-tts")
    foreach ($pkg in $deps) {
        Log "pip install $pkg"
        & python -m pip install --user --disable-pip-version-check $pkg 2>&1 | ForEach-Object { Log "  $_" }
    }
    Log "pip 의존성 설치 완료"
}

# ===== 메인 =====
Log "============================================"
Log "Reaction Bot post-install 시작"
Log "============================================"

if (Test-Java21) {
    Log "Java 21+ 이미 설치됨"
} else {
    Log "Java 21+ 없음 → 설치 진행"
    Install-Java21
}

if (Test-Python) {
    Log "Python 3 이미 설치됨"
} else {
    Log "Python 3 없음 → 설치 진행"
    Install-Python
}

Install-PipDeps

Log "post-install 완료"
