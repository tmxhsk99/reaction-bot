; ============================================================
;  Reaction Bot 인스톨러 (Inno Setup)
;
;  - 사용자 PC에 Java 21 / Python 3 자동 감지
;  - 없으면 공식 사이트에서 다운로드 → silent install
;  - pip 의존성 자동 설치
;  - jar + 런처 + assets를 Program Files\ReactionBot\ 에 배포
;  - 시작 메뉴에 단축키 생성
;
;  빌드: gradle distInstaller  (build.gradle의 태스크가 호출)
; ============================================================

#define MyAppName "Reaction Bot"
#define MyAppVersion "0.0.1"
#define MyAppPublisher "Reaction Bot"
#define MyAppURL "https://github.com/your-org/reaction-bot"
#define MyAppExeName "start.bat"

; Gradle에서 -DProjectRoot=... 로 주입
#ifndef ProjectRoot
  #define ProjectRoot ".."
#endif

[Setup]
AppId={{8D2A4FB2-7AB1-4F2C-89E9-1E5C8CFFB041}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
DefaultDirName={autopf}\ReactionBot
DefaultGroupName={#MyAppName}
DisableDirPage=auto
DisableProgramGroupPage=auto
OutputDir={#ProjectRoot}\build\distributions
OutputBaseFilename=ReactionBot-Setup-{#MyAppVersion}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
; 인스톨러 자체 아이콘 (.exe 파일 아이콘)
SetupIconFile={#ProjectRoot}\installer\icon.ico
; 제어판 "프로그램 추가/제거" 에 표시될 아이콘
UninstallDisplayIcon={app}\icon.ico

[Languages]
Name: "korean"; MessagesFile: "compiler:Languages\Korean.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "{#ProjectRoot}\build\libs\reaction-bot-{#MyAppVersion}-SNAPSHOT.jar"; DestDir: "{app}"; DestName: "reaction-bot.jar"; Flags: ignoreversion
Source: "{#ProjectRoot}\dist\start.bat"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#ProjectRoot}\dist\start.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#ProjectRoot}\dist\README.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#ProjectRoot}\assets\avatar\*.png"; DestDir: "{app}\assets\avatar"; Flags: ignoreversion
Source: "{#ProjectRoot}\assets\bg\README.md"; DestDir: "{app}\assets\bg"; Flags: ignoreversion
Source: "{#ProjectRoot}\installer\post-install.ps1"; DestDir: "{app}\installer"; Flags: ignoreversion
Source: "{#ProjectRoot}\installer\icon.ico"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\Reaction Bot 실행"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"; IconFilename: "{app}\icon.ico"
Name: "{group}\설정 페이지 열기"; Filename: "http://localhost:8080/config"; IconFilename: "{app}\icon.ico"
Name: "{group}\사용자 가이드"; Filename: "{app}\README.txt"
Name: "{group}\{cm:UninstallProgram,{#MyAppName}}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\Reaction Bot"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"; IconFilename: "{app}\icon.ico"; Tasks: desktopicon

[Run]
; 설치 막바지에 PowerShell로 Python/Java 의존성 자동 처리
Filename: "powershell.exe"; \
  Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\installer\post-install.ps1"""; \
  StatusMsg: "Python / Java 의존성 확인 및 설치 중..."; \
  Flags: waituntilterminated runhidden

; 설치 직후 설정 페이지 안내 (사용자가 체크박스로 선택)
Filename: "{app}\{#MyAppExeName}"; \
  Description: "Reaction Bot 바로 실행"; \
  Flags: postinstall nowait skipifsilent unchecked

[UninstallDelete]
Type: filesandordirs; Name: "{app}\tts-output"
Type: files; Name: "{app}\config.yml"
