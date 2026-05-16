============================================================
 Reaction Bot — 빠른 시작 (Windows)
============================================================

[1] 사전 설치 (1회만)
  - Java 21          https://adoptium.net/
  - Python 3.9~3.13  https://www.python.org/
  - Python 패키지:
      pip install --user numpy sounddevice requests faster-whisper webrtcvad-wheels edge-tts

[2] API 키 발급 (무료 tier 있음)
  - Anthropic        https://console.anthropic.com/settings/keys
  - Azure Speech     https://portal.azure.com (Speech services 리소스 + Free F0)

[3] 실행
  - start.bat 더블클릭
  - 5초 뒤 브라우저에 자동으로 설정 페이지 열림
    (http://localhost:8080/config)
  - 설정 페이지에서 API 키 / 이름 / 음성 입력 → 저장
  - 콘솔 창 닫고 다시 start.bat 실행 (설정 적용 위해)

[4] 배경 영상 (선택)
  - assets/bg/ 폴더에 bg.mp4 또는 bg.gif 본인 자산 배치
  - 없어도 동작은 함 (배경 없이 캐릭터만)

[5] OBS 연동
  - 아바타: 브라우저 소스 → URL http://localhost:8080/avatar
  - 화면 캡처: OBS Tools → WebSocket Server Settings → Enable
    (포트 4455 기본값, 비번 설정 시 설정 페이지에 입력)

[6] 끄기
  - 콘솔 창에서 Ctrl+C 또는 창 닫기
  - 설정은 config.yml 에 저장되어 다음 실행에 자동 적용

자세한 내용 / 트러블슈팅:
  https://github.com/<your-repo>/reaction-bot/blob/main/README.md
