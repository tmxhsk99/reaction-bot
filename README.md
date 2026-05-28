# Reaction Bot

방송하면서 내가 말하면 봇 캐릭터(기본 이름: **리봇**)가 화면을 보고 듣고 가끔 리액션해주는 시스템.

```
[내 마이크] → Python STT 워커 (VAD + faster-whisper)
              ↓ 발화 끝나면 POST
            [Spring Boot] ← 화면 캡처 + 발화 텍스트 + 대화 히스토리
              ↓
              Claude API (말할지 / PASS할지 판단)
              ↓ 말할 멘트가 있으면
              Edge TTS (Python) → mp3 → 스피커 → OBS "데스크탑 오디오"
```

STT 워커는 서버 기동 시 **자식 프로세스로 자동 실행**됩니다. 터미널 하나로 끝.

---

## 빠른 시작 (비개발자용)

가장 간단한 방법: **인스톨러(.exe) 한 번 더블클릭**.

1. GitHub Releases에서 `ReactionBot-Setup-x.x.x.exe` 다운로드
2. **더블클릭** — 인스톨러가 자동으로:
   - Java 21이 없으면 다운로드 + 설치
   - Python 3이 없으면 다운로드 + 설치
   - 필요한 Python 패키지(`numpy`, `faster-whisper`, `edge-tts` 등) pip install
   - 앱 파일을 Program Files에 배치 + 시작 메뉴 단축키 생성
3. 시작 메뉴 → **Reaction Bot 실행** → 5초 뒤 브라우저에 설정 페이지가 자동 열림
4. **API 키 입력 + 저장**:
   - Anthropic: https://console.anthropic.com/settings/keys ($5 충전)
   - Azure Speech: https://portal.azure.com (Speech services, Free F0 — 월 50만 자 무료)
5. 콘솔창 닫고 다시 시작 메뉴 → 실행 → 끝

OBS 연동: 브라우저 소스 추가 → `http://localhost:8080/avatar`

> **인스톨러 첫 실행 시 의존성 자동 설치 로그**: `%APPDATA%\ReactionBot\install.log` 에서 확인 가능.

### 또는 zip 번들 (인스톨러 없이)
가벼운 폴더 배치를 원하면 `reaction-bot-x.x.x-bundle.zip` 받아서 압축만 풀고 `start.bat` 실행. 이 경우 Java 21과 Python은 직접 설치 필요.

기능 켜기/끄기, 음성 바꾸기 등은 모두 `http://localhost:8080/config` 에서 가능. 설정은 jar 옆 `config.yml`로 영구 저장됩니다.

---

## 1. 필요한 것

| 항목 | 버전/조건 |
|---|---|
| **JDK** | **21** (Spring Boot 3.3.5는 JDK 17+ 필수, 21 권장) |
| **Python** | **3.9 ~ 3.13** |
| **LLM provider** | 셋 중 하나 선택 — Anthropic Claude (기본) / Google Gemini / 로컬 Ollama. §2.6 참고. Anthropic 쓰면 API 키 필요 (아래 ⚠️). 로컬 Ollama 쓰면 API 키 불필요 (대신 GPU 있는 PC 권장) |
| **마이크** | OS 기본 입력으로 잡히면 자동 인식 |
| **OS** | Windows 11 기준으로 작성. macOS/Linux도 동작은 함 |

> ⚠️ **Claude Pro ≠ API**: claude.ai 채팅용 월 구독(Pro)과 API 호출용 크레딧은 결제가 분리되어 있습니다. 이 봇은 API를 쓰므로 console.anthropic.com → Plans & Billing에서 **API 크레딧을 따로 충전**해야 합니다. 최소 $5 부터 가능.

---

## 2. 셋업

### 2.1 JDK 21 설치

이미 21 깔려있으면 건너뜀. winget 사용:

```powershell
winget install --id EclipseAdoptium.Temurin.21.JDK
```

설치 위치 예시: `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`

**Gradle이 어떤 JDK를 쓰는지 지정**:
- 시스템 `JAVA_HOME`을 21로 가리키면 끝이거나,
- `gradle.properties.example`을 `gradle.properties`로 복사한 뒤 자기 환경 경로를 채워 넣음:
  ```bash
  cp gradle.properties.example gradle.properties
  # 그 후 파일 열어 org.gradle.java.home=... 자기 JDK 경로로 수정
  ```
  `gradle.properties`는 사용자별 경로라 git에 커밋되지 않습니다.

### 2.2 Python 의존성

```powershell
pip install --user numpy sounddevice requests faster-whisper webrtcvad-wheels edge-tts
```

> `webrtcvad`(원본)는 Python 3.13에서 wheel이 없어 빌드 실패합니다. `webrtcvad-wheels` 포크를 쓰세요. Python 3.11 이하면 `webrtcvad`도 가능.

faster-whisper는 처음 실행 시 모델을 자동 다운로드합니다 (`small` ≈ 500MB).

### 2.3 환경변수 (API 키 + 캐릭터/스트리머 이름)

세 가지를 환경변수로 주입합니다. **`ANTHROPIC_API_KEY`만 필수**, 나머지는 미설정 시 기본값 사용.

| 환경변수 | 용도 | 기본값 (미설정 시) |
|---|---|---|
| `ANTHROPIC_API_KEY` | Claude API 키 (필수) | 없음 → 호출 시 401 |
| `AZURE_SPEECH_KEY` | Azure Speech 리소스 키 (필수, TTS용) | 없음 → TTS 실패. 발급 방법은 §2.5 참고 |
| `AZURE_SPEECH_REGION` | Azure Speech 리소스 region | `koreacentral` |
| `BOT_NAME` | 봇 이름 | `리봇` |
| `STREAMER_NAME` | 스트리머 이름 | `로크만` |
| `OBS_PASSWORD` | OBS WebSocket 비밀번호 | 없음 (OBS에 비번 안 걸어두면 OK) — 자세히는 §7 참고 |

**영구 설정 (PowerShell, User 범위)** — 새 터미널/IntelliJ에서 자동 적용:
```powershell
[Environment]::SetEnvironmentVariable("ANTHROPIC_API_KEY", "sk-ant-...", "User")
[Environment]::SetEnvironmentVariable("BOT_NAME", "원하는봇이름", "User")
[Environment]::SetEnvironmentVariable("STREAMER_NAME", "원하는스트리머이름", "User")
```

**현재 세션만 임시 설정**:
```powershell
$env:ANTHROPIC_API_KEY = "sk-ant-..."
$env:BOT_NAME = "원하는봇이름"
$env:STREAMER_NAME = "원하는스트리머이름"
gradle bootRun
```

**IntelliJ에서 실행하는 경우**:
Run/Debug Configurations → Spring Boot 설정 → **Environment variables**에 `ANTHROPIC_API_KEY=sk-ant-...;BOT_NAME=...;STREAMER_NAME=...` 추가.

### 2.4 Azure Speech (TTS) 키 발급 — 1회 설정

> **TTS provider를 `edge`로 쓰면 이 단계는 건너뛰어도 됩니다.** 키 없이 무료로 동작하는 대신 한국어 음성이 3개뿐이라는 제약이 있습니다 (§2.5 참고).

`tts.provider=azure` (기본)로 쓸 때만 필요. 무료 tier로 **월 50만 자**까지 합성 가능 (이 봇 용도면 사실상 무제한).

1. **Azure 계정** — https://azure.microsoft.com 에서 무료 가입 (신용카드 등록 필요하지만 무료 tier만 쓰면 청구 안 됨)
2. **Speech 리소스 생성**:
   - https://portal.azure.com → 상단 검색창에 `Speech services` → **Create**
   - **Resource group**: 없으면 새로 만들기
   - **Region**: `Korea Central` 권장 (한국에서 가까움)
   - **Pricing tier**: `Free F0` (월 50만 자 무료)
   - **Create + Review** → **Create**
3. **키 확인**:
   - 만들어진 리소스로 들어가서 좌측 메뉴 → **Keys and Endpoint**
   - `KEY 1` 또는 `KEY 2` 중 아무거나 복사 (64자 hex 문자열)
   - **Location/Region**도 확인 (예: `koreacentral`)
4. **환경변수 등록** — §2.3에 추가:
   ```powershell
   [Environment]::SetEnvironmentVariable("AZURE_SPEECH_KEY", "여기에키", "User")
   [Environment]::SetEnvironmentVariable("AZURE_SPEECH_REGION", "koreacentral", "User")
   ```

비용 모니터링: portal.azure.com → Speech 리소스 → **Metrics**에서 사용량 확인. 무료 tier 초과 시 자동으로 합성 거부 (요금 폭탄 X).

### 2.5 (선택) 캐릭터 / 음성 설정

| 파일 | 내용 |
|---|---|
| [src/main/resources/application.yml](src/main/resources/application.yml) | 봇/스트리머 이름 기본값, 음성, 모델, STT 옵션, 모니터 인덱스 |
| [src/main/resources/character.yml](src/main/resources/character.yml) | 봇 성격, 말투, PASS 기준 (시스템 프롬프트 본문) |

**이름 변경 우선순위 (Spring placeholder 규칙)**:
1. 환경변수 `BOT_NAME` / `STREAMER_NAME` → 있으면 최우선
2. 없으면 [application.yml](src/main/resources/application.yml)의 `reaction-bot.character.name` / `streamer-name` 값
3. 시스템 프롬프트 안의 `{name}` / `{streamer}` placeholder가 위 값으로 자동 치환됨 — 프롬프트 본문은 건드릴 필요 없음

**TTS provider 선택** ([application.yml](src/main/resources/application.yml)의 `tts.provider`):

| provider | 비용 | 한국어 음성 | API 키 |
|---|---|---|---|
| `azure` (기본) | 무료 tier 월 50만 자 | 10개 (다양) | 필요 (§2.4 참고) |
| `edge` | 완전 무료 | 3개 (SunHi/InJoon/HyunsuMultilingual) | 불필요 |

**음성 변경** (`application.yml`의 `tts.voice`) — provider별 가용 음성:

| Provider | 성별 | 음성 |
|---|---|---|
| azure | 여 | `ko-KR-JiMinNeural` (차분, 기본), `ko-KR-SunHiNeural` (밝음), `ko-KR-YuJinNeural` (발랄), `ko-KR-SeoHyeonNeural`, `ko-KR-SoonBokNeural` |
| azure | 남 | `ko-KR-InJoonNeural`, `ko-KR-BongJinNeural` (중후), `ko-KR-GookMinNeural` (친근), `ko-KR-HyunsuNeural`, `ko-KR-HyunsuMultilingualNeural` (다국어) |
| edge | 여 | `ko-KR-SunHiNeural` (밝음, 유일) |
| edge | 남 | `ko-KR-InJoonNeural`, `ko-KR-HyunsuMultilingualNeural` |

> provider와 음성이 호환되지 않으면 TTS가 실패합니다. provider 바꿀 때 음성도 같이 확인해주세요.

### 2.6 LLM Provider 선택

봇의 두뇌. 세 가지 백엔드를 갈아끼울 수 있습니다.

| Provider | 비용 | 화면 인식 | 호출 패턴 | 셋업 난이도 |
|---|---|---|---|---|
| **anthropic** (Claude, 기본) | 유료 (~$0.01~0.03/호출) | ✅ vision | 2단계 (Haiku triage → Sonnet 생성) | 키 발급만 |
| **gemini** (Google) | 유료 (Claude의 약 1/10) | ✅ vision | 2단계 (Flash-Lite triage → Flash 생성) | 키 발급만 |
| **ollama** (로컬 self-host) | **무료** (전기/GPU만) | ✅/❌ 모델에 따라 | **1단계** (triage 생략. 가장 빠름) | Ollama 설치 + 모델 pull |

**스위치 위치** ([application.yml](src/main/resources/application.yml)):
```yaml
reaction-bot:
  llm:
    provider: anthropic   # 또는 gemini, ollama
```

provider별 디테일은 각 파일로 분리되어 있습니다:
- [anthropic.yml](src/main/resources/anthropic.yml) — Claude 키/모델
- [gemini.yml](src/main/resources/gemini.yml) — Gemini 키/모델
- [ollama.yml](src/main/resources/ollama.yml) — 로컬 Ollama URL/모델/vision/적극성

#### 로컬 Ollama 셋업

**언제 쓰면 좋은가**: API 비용 없이 무제한 호출하고 싶을 때. 단점은 모델 품질이 Claude/Gemini보다 다소 낮고, 응답 속도는 PC GPU 성능에 의존.

**1) Ollama 설치** — https://ollama.com/download

로컬 PC 또는 내부망의 다른 PC에 설치. 다른 PC에서 띄울 거면 외부 접근 허용:
```powershell
# 환경변수 OLLAMA_HOST를 0.0.0.0:11434로 설정 후 Ollama 재시작
[Environment]::SetEnvironmentVariable("OLLAMA_HOST", "0.0.0.0:11434", "User")
```

**2) 모델 pull** — 비전 모델 권장 (게임 화면 인식 위해):
```powershell
ollama pull qwen3-vl:8b      # 추천: 비전 지원 + 적당한 사이즈 (~5GB)
# 또는
ollama pull qwen3-vl:4b      # 더 가벼움 (VRAM 부족 시)
ollama pull qwen2.5vl:7b     # 이전 세대 VL
ollama pull qwen3:8b         # text-only. 화면 인식 안 되지만 더 빠름
```

> qwen3 시리즈는 **text-only**, qwen3-vl / qwen2.5vl만 비전 지원합니다. 비전 안 쓰면 [ollama.yml](src/main/resources/ollama.yml)에서 `vision: false`로 끄면 캡처 자체를 생략해 더 빠르게 동작합니다.

**3) provider 스위치 + URL 설정**

[application.yml](src/main/resources/application.yml):
```yaml
reaction-bot:
  llm:
    provider: ollama
```

[ollama.yml](src/main/resources/ollama.yml) (또는 환경변수 `OLLAMA_BASE_URL`):
```yaml
reaction-bot:
  ollama:
    base-url: ${OLLAMA_BASE_URL:http://192.168.0.8:11434}   # 같은 PC면 http://localhost:11434
    model: qwen3-vl:8b
    vision: true                # 비전 모델이면 true, text-only면 false
    temperature: 0.9            # 0.7=얌전, 0.9=다채로움
    top_p: 0.95
    assertive: true             # true=로컬 전용 적극성 nudge (PASS 줄이고 더 자주 말함)
    think: false                # qwen3 thinking 모드. false=꺼서 응답 속도↑
```

**4) 실행 + 확인**

```powershell
gradle bootRun
```

로그에 다음 줄이 보이면 성공:
```
LLM provider=ollama. baseUrl=http://192.168.0.8:11434, model=qwen3-vl:8b, single-call mode (triage off, vision=on)
```

#### Ollama 모드의 동작 차이

로컬은 호출 비용이 없으므로 코드가 자동으로 최적화 패턴을 바꿉니다:

| 항목 | Claude/Gemini | Ollama |
|---|---|---|
| LLM 호출 횟수 | 매 발화당 최대 2회 (triage + 생성) | **1회만** (triage 생략) |
| 화면 캡처 | 매번 | `vision: true`일 때만 |
| 적극성 | 시스템 prompt 기본 | `assertive: true`면 추가 nudge 주입 → PASS 빈도↓ |
| 응답 다양성 | API 디폴트 | `temperature` / `top_p`로 직접 조정 |

#### 모델 선택 팁

| 상황 | 권장 모델 |
|---|---|
| GPU VRAM ≥ 16GB (RTX 4080+) | `qwen3-vl:8b` 또는 `qwen3-vl:30b` |
| GPU VRAM 8~12GB (RTX 3060+) | `qwen3-vl:8b` |
| GPU VRAM 6GB 이하 / CPU 전용 | `qwen3-vl:4b` 또는 `qwen3-vl:2b` |
| 화면 인식 필요 없음 (음성만) | `qwen3:8b` + `vision: false` |

#### 트러블슈팅

| 증상 | 원인 / 해결 |
|---|---|
| `Ollama HTTP 404 ... model 'xxx' not found` | `ollama pull xxx` 안 함. 또는 모델 태그 오타 |
| `Connection refused` 또는 timeout | Ollama 서버 안 떠 있음 / URL 오타 / 방화벽. 다른 PC면 `OLLAMA_HOST=0.0.0.0:11434` 설정했는지 확인 |
| 응답이 `<think>...</think>`로 채워짐 | `ollama.yml`에서 `think: false` 설정 (구버전 Ollama는 무시될 수 있음 — 그 경우 `ollama update`) |
| 응답이 한참 걸림 (수십 초~) | thinking 모드 켜져 있거나 모델이 너무 큼. `think: false` + 작은 모델로 |
| PASS만 자꾸 뱉음 | `assertive: true` 확인. 또는 `application.yml`의 `speech.nudge-after-pass-count`를 1~2로 낮추기 |

---

## 3. 실행

### 개발 모드 (소스에서 직접)
IntelliJ Run 버튼 또는:

```powershell
gradle bootRun
```

이 한 번으로 **Spring Boot 서버 + STT 워커**가 같이 뜹니다.

### 배포 빌드
사용자 배포용 zip / 인스톨러 빌드 방법은 **§10 빌드 & 배포** 섹션 참고.

정상 기동 로그 순서:
```
Started ReactionBotApplication in X.X seconds
STT 워커 시작: python scripts/stt_worker.py --server-url ...
[stt] 🤖 Whisper 모델 로딩: small (int8, device=cpu)
[stt] ✅ 모델 로딩 완료
[stt] 🎙️ 마이크 시작 (device=None)    ← None은 "OS 기본 마이크" 의미. 정상.
```

마이크에 말하면:
```
[stt] 🎤 발화 감지
[stt] 📝 발화 완료 (2.3s). STT 시작...
[stt] 📝 STT 결과 (1.8s): '안녕 리봇야'
STT 발화 수신: '안녕 리봇야'
Claude 호출. 메시지 수: 1, 이미지: true
봇 raw 응답: 어 왔어? 오랜만이네 ㅋㅋ
[stt] 📡 서버 응답: SPOKE | 어 왔어? 오랜만이네 ㅋㅋ
```
SPOKE면 스피커로 봇 목소리, PASS면 봇이 "지금은 침묵이 낫다"고 판단한 정상 케이스.

### STT 워커 끄고 싶을 때 (마이크 없이 개발)

[application.yml](src/main/resources/application.yml):
```yaml
reaction-bot:
  stt:
    auto-start: false
```

---

## 4. 트러블슈팅

### "Could not resolve org.springframework.boot:spring-boot-gradle-plugin"
JDK 17 미만으로 Gradle 데몬이 돌고 있음. → **2.1 절** 다시 (JDK 21 설치 + gradle.properties).

### `ModuleNotFoundError: No module named 'numpy' / 'edge_tts' / ...`
Python 의존성 누락. → **2.2 절** 다시. `pip install --user` 사용 권장 (특히 Windows Microsoft Store Python).

### `RuntimeError: Library cublas64_12.dll is not found`
faster-whisper가 GPU(CUDA)를 잡으려다 실패. `application.yml`에서 `stt.device: cpu`로 고정되어 있어야 함 (기본값). CUDA 12 + cuBLAS가 제대로 깔려있는 NVIDIA GPU 환경이면 `cuda`로 바꾸고 `compute-type: float16` 같이 변경.

### `Your credit balance is too low to access the Anthropic API`
Pro 구독과 별개. console.anthropic.com → Plans & Billing → **Add credits**에서 충전. → 위 ⚠️ 참고.

### `화면 캡처 실패. 이미지 없이 진행. ...HeadlessException...`
Spring Boot가 headless 모드로 뜸. [ReactionBotApplication.java](src/main/java/com/jhkim/reactionbot/ReactionBotApplication.java)에 `app.setHeadless(false)` 적용되어 있어야 함. 멀티모니터에서 다른 모니터를 잡고 싶으면 `application.yml`의 `screen.monitor-index`를 0, 1, 2…로.

### 봇이 자기 목소리에 또 반응함 (피드백 루프)
가능하면 **헤드폰 사용**. 스피커 출력이면 `application.yml`의 `speech.grace-period-ms`를 1500~2000 정도로 늘리면 좀 완화됨.

### 마이크 디바이스 명시적으로 고르기
```powershell
python scripts/list_devices.py
```
출력에서 원하는 인덱스 확인 후 [application.yml](src/main/resources/application.yml):
```yaml
reaction-bot:
  stt:
    device-index: 1   # Yeti 마이크 같은 특정 디바이스
```

---

## 5. STT 정확도 튜닝

### Whisper 모델 선택 ([application.yml](src/main/resources/application.yml)의 `stt.model`)

| 모델 | 크기 | CPU 속도 | 정확도 |
|---|---|---|---|
| `tiny` | 75MB | 매우 빠름 | 낮음 |
| `base` | 140MB | 빠름 | 보통 |
| `small` | 500MB | 보통 | 좋음 (기본 추천) |
| `medium` | 1.5GB | 느림 | 매우 좋음 |
| `large-v3` | 3GB | 매우 느림 | 최상 |

게임이랑 같이 돌리면 `base` 또는 `small`이 적당.

### VAD 민감도 ([application.yml](src/main/resources/application.yml)의 `stt.vad-aggressiveness`)
- `0` 관대 (조용한 환경)
- `2` 기본
- `3` 엄격 (게임 BGM 시끄러울 때)

---

## 6. 엔드포인트

| Method | Path | 용도 |
|---|---|---|
| POST | `/api/react/speech` | STT 워커가 발화 보냄. `{"text": "..."}` |
| POST | `/api/reset` | 대화 히스토리 초기화 |
| GET | `/api/status` | 현재 봇 발화 중인지 / 히스토리 턴 수 |
| GET | `/api/avatar/events` | 아바타 페이지가 구독하는 SSE 스트림 (`speak_start` / `speak_end` 이벤트) |
| GET | `/avatar/` | 아바타 HTML 페이지 (OBS Browser Source URL) |
| GET | `/config/` | 사용자 설정 UI (API 키, 이름, 음성 등) |
| GET | `/api/config` | 현재 설정 JSON (시크릿 마스킹) |
| POST | `/api/config` | 설정 저장 (jar 옆 `config.yml`에 영구화) |

수동 테스트:
```powershell
curl.exe -X POST http://localhost:8080/api/react/speech `
  -H "Content-Type: application/json" `
  -d '{"text":"안녕 리봇야 잘 보여?"}'
```

---

## 7. OBS 연동

### 음성 송출
설정 없음. OBS의 "데스크탑 오디오" 소스가 켜져 있으면 봇 음성이 자동으로 송출됩니다. 본인 마이크는 OBS에서 따로 "마이크 소스"로 추가하면 됨 (원래 하던 대로).

### 송출 화면을 봇에게 보내기 (OBS WebSocket)
봇은 기본적으로 OBS WebSocket으로 **현재 송출 중인 씬**을 캡처해서 Claude에 보냅니다. 즉 시청자가 보는 화면 그대로(오버레이/필터 다 포함). 모니터 전체를 보내는 게 아니라서 깔끔합니다.

**OBS 쪽 셋업** (1회만):
1. OBS 28+ (내장 WebSocket Server 사용 — 별도 플러그인 X)
2. 메뉴 → **Tools → WebSocket Server Settings**
3. ☑ **Enable WebSocket server** 체크
4. **Server Port**: `4455` (기본값 유지)
5. **Server Password**:
   - 비워두면 인증 없이 연결 (로컬만 쓸 때 OK)
   - 설정하면 환경변수 `OBS_PASSWORD`로 같이 전달해야 함:
     ```powershell
     [Environment]::SetEnvironmentVariable("OBS_PASSWORD", "여기에비번", "User")
     ```
6. **Apply**

**Spring 쪽 설정** ([application.yml](src/main/resources/application.yml)):
```yaml
reaction-bot:
  screen:
    source: "obs"                # 기본값. 변경 불필요.
    obs:
      host: "localhost"
      port: 4455
      source-name: ""            # 빈 값 = 현재 프로그램 씬 자동 사용 (추천)
                                 # 특정 씬/소스만 캡처하려면 그 이름 입력 (예: "Scene 1")
      timeout-ms: 3000
```

**OBS 없이 모니터 전체로 돌아가려면**:
```yaml
reaction-bot:
  screen:
    source: "robot"
    monitor-index: 0
```

### 트러블슈팅
- `OBS 캡처 실패: ... Connection refused` → OBS가 실행 중인지, WebSocket Server가 켜져 있는지 확인.
- `OBS WebSocket이 비밀번호를 요구하는데 OBS_PASSWORD가 설정되지 않음` → 위 5단계 비밀번호 설정 또는 OBS WebSocket 비번 제거.
- `OBS 응답 타임아웃: GetSourceScreenshot` → 씬에 비어있거나 (소스 없음), 매우 큰 해상도일 가능성. OBS에서 해당 씬 출력 미리보기가 보이는지 확인.

### 아바타 (말할 때 입 움직이는 캐릭터)
봇이 말하는 동안 캐릭터 입이 움직이고 평소엔 눈을 깜박이는 오버레이를 OBS에 띄울 수 있습니다.

> **이미지 에셋 준비/교체/디버깅은 §8 이미지 에셋 가이드** 참고. 여기선 OBS 쪽 셋업만.

**OBS 셋업**:
1. 서버 띄움 (`gradle bootRun`)
2. 브라우저로 `http://localhost:8080/avatar/` 열어서 캐릭터 보이는지 확인
3. OBS → **소스 추가** → **브라우저(Browser)** → 새로 만들기:
   - **URL**: `http://localhost:8080/avatar/`
   - **너비/높이**: 정사각형으로. 화질 살리려면 이미지 원본 크기(1600×1600), 가벼움 우선이면 800×800 정도. 페이지가 `100vmin`으로 자동 스케일되므로 어느 크기든 캐릭터가 영역 가득 채움.
   - ☐ "OBS가 표시되지 않을 때 소스 종료" — 끄는 게 SSE 연결 유지에 안전 (옵션)
4. 씬에서 위치/크기 자유롭게 조정. Browser Source 자체 크기를 1600으로 만들어두고 OBS 씬에서 작게 줄여 합성하면 화질 손실 없음.

**동작**:
- 평소: 눈 깜박임 (3~7초 랜덤), 캐릭터 살짝 위아래로 흔들림(idle bobbing)
- 봇이 말할 때: 입이 closed → half → open → half 사이클 (~110ms)로 움직이고 흔들림 진폭 커짐
- TTS 끝나면 자동으로 입 닫음 상태로 복귀

서버 측 동작은 [ReactionOrchestrator](src/main/java/com/jhkim/reactionbot/service/ReactionOrchestrator.java)에서 `ttsService.speak()` 직전/직후에 SSE 이벤트를 푸시 → [/static/avatar/index.html](src/main/resources/static/avatar/index.html)가 받아서 애니메이션 토글.

---

## 8. 이미지 에셋 가이드

봇 화면(`http://localhost:8080/avatar/`)을 구성하는 이미지 파일들의 셋업·교체·커스터마이징 가이드.

### 8.1 폴더 구조

```
프로젝트루트/
├── assets/
│   ├── avatar/                    ← 캐릭터 파트 이미지 (7장, 파일시스템)
│   │   ├── base.png
│   │   ├── mouth-closed.png
│   │   ├── mouth-half.png
│   │   ├── mouth-open.png
│   │   ├── eye-open.png
│   │   ├── eye-half.png
│   │   └── eye-closed.png
│   └── bg/                        ← 배경 (gif 또는 mp4)
│       ├── bg.mp4                 (권장)
│       └── bg.gif                 (대안)
└── src/main/resources/static/avatar/
    └── index.html                 ← 렌더링 페이지 (코드, 수정 불필요)
```

**파일시스템 서빙**: `assets/` 하위 이미지들은 JAR 안이 아니라 **프로젝트 루트의 파일시스템**에서 직접 서빙됩니다. 그래서 교체 시 **빌드/재기동 없이 브라우저 새로고침(Ctrl+Shift+R)만으로** 즉시 반영됩니다 ([WebConfig.java](src/main/java/com/jhkim/reactionbot/config/WebConfig.java) 참고).

### 8.2 캐릭터 파트 이미지

#### 필수 7장

| 파일명 | 내용 | 표시 시점 |
|---|---|---|
| `base.png` | 캐릭터 본체 (입·눈 부분은 비움) | 항상 |
| `mouth-closed.png` | 입 닫은 모습만 | 평소, TTS 끝났을 때 |
| `mouth-half.png` | 입 반쯤 벌린 모습만 | TTS 중 (closed → half → open → half 사이클) |
| `mouth-open.png` | 입 크게 벌린 모습만 | TTS 중 |
| `eye-open.png` | 눈 뜬 모습만 | 평소 |
| `eye-half.png` | 눈 반쯤 감은 모습만 | 깜박이는 도중 |
| `eye-closed.png` | 눈 감은 모습만 | 깜박이는 순간 |

#### 공통 규격

- **포맷**: PNG **알파 채널(투명 배경)** 필수
- **크기**: 7장 모두 **동일한 캔버스 크기**. 현재 1600×1600 사용 중 (1024×1024 도 OK, 작아도 됨. 크기만 통일하면 됨)
- **좌표**: 각 파트 이미지는 해당 부위만 그리고 나머지는 투명. **`base.png`와 같은 좌표 위치에 입/눈이 있어야** 겹쳤을 때 어긋나지 않음

#### 정렬 팁 (포토샵/Krita/Affinity 등)

좌표 정렬을 가장 쉽게 하는 방법:
1. `base.png`를 만들 때 입·눈 자리는 **빈 공간으로 비워둠** (실제로 그 부분만 지움)
2. 같은 캔버스에서 **새 레이어**로 `mouth-closed`를 그림 — 그 레이어만 보이게 하고 export
3. 다시 새 레이어에서 `mouth-half`, `mouth-open` 그리기 (같은 위치 기준)
4. `eye-*` 도 동일하게 작업

이렇게 하면 모든 파트가 `base.png`와 자동으로 정확히 정렬됨.

#### 레이어 z-index 순서

위에서부터 (뒤 → 앞):
```
0. bg.mp4 / bg.gif       (배경)
1. base.png              (몸/얼굴)
2. mouth-*.png           (입, 시점별로 한 장만)
3. eye-*.png             (눈, 시점별로 한 장만)
```

### 8.3 배경 이미지/비디오

> ⚠️ **저장소에는 배경 파일이 포함되어 있지 않습니다.** 라이선스 문제로 본인 자산만 사용해주세요. `.gitignore`에 `assets/bg/*` 가 들어 있어 어떤 미디어든 git에 올라가지 않습니다.

#### 위치
`assets/bg/` 폴더에 배치. HTML은 `/bg/bg.mp4`를 우선 참조함 (참고: [index.html](src/main/resources/static/avatar/index.html)).

#### 권장 형식: MP4
- GIF 대비 같은 화질에서 **약 1/10 용량**
- 부드러운 재생 (GIF는 컬러 256색 제한)
- 무한 루프 자동 재생됨 (`<video loop muted>`)

#### GIF → MP4 변환 (ffmpeg)
ffmpeg 없으면 설치:
```powershell
winget install Gyan.FFmpeg
```

변환:
```powershell
ffmpeg -i "assets/bg/bg.gif" -movflags faststart -pix_fmt yuv420p `
       -vf "scale=trunc(iw/2)*2:trunc(ih/2)*2" "assets/bg/bg.mp4"
```

#### 다른 파일명 쓰고 싶을 때
[index.html](src/main/resources/static/avatar/index.html) 의:
```html
<video class="bg" src="/bg/bg.mp4" autoplay loop muted playsinline></video>
```
에서 `src` 경로만 바꾸면 됩니다. `/bg/` 하위 어떤 파일이든 서빙됨 (png, jpg, gif, webp, mp4 모두).

#### 배경 없이 투명하게 (OBS 합성용)
배경 줄을 통째로 주석 처리:
```html
<!-- <video class="bg" src="/bg/bg.mp4" autoplay loop muted playsinline></video> -->
```
그러면 페이지 배경이 투명해져서 OBS에서 다른 소스와 자유롭게 합성 가능.

### 8.4 교체·테스트 흐름

1. `assets/avatar/` 또는 `assets/bg/` 에 새 파일 덮어쓰기
2. 브라우저에서 `http://localhost:8080/avatar/` 열고 **`Ctrl+Shift+R`** (강력 새로고침)
3. 끝. 서버 재기동 안 해도 됨.

OBS Browser Source의 경우: OBS 소스 우클릭 → **새로고침**을 누르면 동일하게 반영.

### 8.5 커스터마이징 아이디어

- **다른 포즈/표정 세트**: 폴더를 분리(예: `assets/avatar/normal/`, `assets/avatar/surprised/`)하고 HTML에서 상태별로 src를 바꿔주면 됨. Claude 응답에 감정 태그 받게 프롬프트 살짝 수정 → SSE 이벤트에 태그 같이 전달.
- **레이어 추가** (예: 머리카락, 액세서리): `index.html` 의 `.avatar` div 안에 `<img class="layer" src="/avatar/...">` 한 줄 추가하면 됨. z-index 순서대로 쌓임.
- **애니메이션 조정**: [index.html](src/main/resources/static/avatar/index.html) 의 `@keyframes idle-sway` (평소 흔들림), `@keyframes speak-bob` (말할 때 튐), `MOUTH_FRAME_MS` (입 프레임 교체 속도, 기본 110ms) 같은 숫자만 조절.

### 8.6 디버깅

브라우저로 `http://localhost:8080/avatar/` 열고 **F12 → Console / Network** 탭:

| 증상 | 원인 / 해결 |
|---|---|
| 캐릭터가 안 보임, base.png 등 404 | 파일 경로 확인. 정확히 `assets/avatar/base.png` 인지. 대소문자 구분 |
| 일부 파트만 안 보임 (예: 입만 없음) | 그 파일만 누락. 파일명 오타 가능 (`mouth-close.png` ≠ `mouth-closed.png`) |
| 입/눈은 보이는데 위치가 어긋남 | 캔버스 크기 불일치 또는 좌표 정렬 안 됨. 8.2 정렬 팁 참고 |
| 캐릭터 주변에 흰 박스가 보임 | PNG에 알파 채널이 빠짐. 저장 시 "투명도 보존" 옵션 켜기 |
| 배경 비디오가 안 재생됨 | 브라우저 콘솔에 오류 확인. `muted` 속성 빠지면 autoplay 차단됨 (`<video ... muted>` 필수) |
| 새 파일 덮어썼는데 안 바뀜 | 강력 새로고침(Ctrl+Shift+R) 또는 OBS 소스 우클릭 → 새로고침. 일반 새로고침은 캐시 때문에 안 될 수 있음 |

---

## 9. 비용 감 (참고)

`claude-sonnet-4-6` 기준 호출당 대략 **$0.01 ~ 0.03** (스크린샷 + 짧은 발화 + 200토큰 응답).
$5만 충전해도 수백~수천 번 호출 가능.

비용 더 줄이려면 [anthropic.yml](src/main/resources/anthropic.yml)에서 모델 변경:
```yaml
reaction-bot:
  anthropic:
    model: claude-haiku-4-5             # 약 1/5 가격
```

또는 **provider를 통째로 바꿔서** 비용 0 / 더 저렴하게:
- **로컬 Ollama** (`llm.provider: ollama`) — 호출 비용 **$0**. GPU 있는 PC만 있으면 됨. §2.6 참고
- **Gemini Flash-Lite** (`llm.provider: gemini`) — Claude Haiku의 약 1/2 가격

---

## 10. 빌드 & 배포

개발자가 새 버전을 빌드해 GitHub Releases에 올리는 전체 흐름.

### 10.1 빌드 명령 한눈에

| 명령 | 산출물 | 크기 | 용도 |
|---|---|---|---|
| `gradle bootRun` | (없음, 그 자리에서 실행) | — | 개발 중 실행 |
| `gradle bootJar` | `build/libs/reaction-bot-x.x.x.jar` | ~48MB | 실행 가능 fat JAR |
| `gradle distBundle` | `build/distributions/reaction-bot-x.x.x-bundle.zip` | ~45MB | 압축 풀고 `start.bat` 실행 (Java/Python은 사용자가 직접 설치) |
| `gradle distInstaller` | `build/distributions/ReactionBot-Setup-x.x.x.exe` | ~43MB | 더블클릭 인스톨러. Java/Python 자동 설치 + 시작메뉴 등록 |

`distInstaller`가 `bootJar`를 의존해서 호출하므로 한 줄로 모두 빌드됨.

### 10.2 사전 요건

| 빌드 종류 | 필요 도구 |
|---|---|
| bootJar / bootRun / distBundle | JDK 21 만 있으면 OK |
| distInstaller | + **Inno Setup 6** |

Inno Setup 설치:
```powershell
winget install JRSoftware.InnoSetup
```
설치 위치: 보통 `%LOCALAPPDATA%\Programs\Inno Setup 6\`. Gradle 태스크가 자동 탐색합니다.

### 10.3 인스톨러가 자동으로 처리하는 것
`distInstaller`로 만든 `.exe` 인스톨러를 사용자가 더블클릭하면:

1. **Java 21+ 감지** → 없으면 [Eclipse Adoptium](https://adoptium.net) MSI 다운로드 + silent install
2. **Python 3.x 감지** → 없으면 [python.org](https://www.python.org) 인스톨러 다운로드 + silent install
3. **pip 의존성 자동 설치**: `numpy`, `sounddevice`, `requests`, `faster-whisper`, `webrtcvad-wheels`, `edge-tts`
4. **앱 파일 배치**: `C:\Program Files\ReactionBot\` (사용자 권한이면 `%LOCALAPPDATA%\Programs\ReactionBot\`)
5. **시작 메뉴 등록**: `Reaction Bot 실행`, `설정 페이지 열기`, `사용자 가이드`, `제거`
6. **(옵션) 바탕화면 아이콘**

설치 로그: `%APPDATA%\ReactionBot\install.log` (Java/Python 감지/설치 결과 기록).

### 10.4 버전 변경

[build.gradle](build.gradle) 의 `version` 값 수정:
```gradle
version = '0.0.2-SNAPSHOT'   // ← 여기
```

그리고 [installer/installer.iss](installer/installer.iss) 의 `MyAppVersion` 도 같이:
```
#define MyAppVersion "0.0.2"
```

> 참고: `installer.iss` 에서 jar 파일명을 `reaction-bot-{#MyAppVersion}-SNAPSHOT.jar` 패턴으로 참조하므로 두 값이 일치해야 빌드됨.

### 10.5 GitHub Releases 업로드 흐름

```powershell
# 1. 버전 올리고 빌드
# (build.gradle / installer.iss 의 버전 두 군데 수정)
gradle clean distBundle distInstaller

# 2. 산출물 확인
ls build/distributions/

# 3. 태그 + push (예: v0.0.2)
git add build.gradle installer/installer.iss
git commit -m "Release v0.0.2"
git tag v0.0.2
git push origin main --tags

# 4. GitHub Release 생성 (gh CLI)
gh release create v0.0.2 `
    --title "Reaction Bot v0.0.2" `
    --notes "변경 내역 ..." `
    build/distributions/ReactionBot-Setup-0.0.2.exe `
    build/distributions/reaction-bot-0.0.2-SNAPSHOT-bundle.zip
```

웹 UI로도 가능: GitHub 리포 → **Releases → Draft a new release** → 태그 선택 → 두 파일 드래그 → Publish.

### 10.6 인스톨러 커스터마이징

[installer/installer.iss](installer/installer.iss) 의 주요 변수:

| 변수 | 의미 |
|---|---|
| `MyAppName` | 표시 이름 ("Reaction Bot") |
| `MyAppVersion` | 버전 |
| `MyAppPublisher` | 게시자 (제어판에 표시) |
| `MyAppURL` | 홈페이지 URL (제어판 링크) |
| `DefaultDirName` | 설치 폴더 (`{autopf}` = Program Files, `{userpf}` = 사용자 폴더) |
| `SetupIconFile` | 인스톨러 .exe 자체 아이콘 (`installer/icon.ico`) |
| `[Tasks]` | 설치 옵션 체크박스 (예: 바탕화면 아이콘) |

아이콘 변경: [installer/icon.ico](installer/icon.ico) 를 새 .ico 파일로 덮어쓰기. 다중 해상도(16×16, 32×32, 48×48, 256×256) 포함 권장. PNG → ICO 변환은 https://icoconvert.com 또는 ImageMagick으로.

### 10.7 디지털 서명 (선택)

서명 없이 배포하면 사용자가 인스톨러 실행 시 Windows SmartScreen 경고 (`Windows의 PC 보호`) → "자세히 → 실행" 클릭 필요.

| 방법 | 비용 | SmartScreen 즉시 통과 |
|---|---|---|
| 미서명 | 무료 | ❌ (사용자가 우회 가능) |
| 자체 서명 | 무료 | ❌ |
| [SignPath Foundation](https://signpath.org) | 무료 (오픈소스만) | ❌ 점차 평판 빌드업 |
| OV 코드사이닝 (Sectigo/SSL.com 등) | $200~400/년 | ❌ 평판 빌드업 필요 |
| EV 코드사이닝 | $400~700/년 | ✅ 즉시 통과 (HW 토큰 필요) |

서명 명령 (인증서 받은 후):
```powershell
signtool sign /f cert.pfx /p <비번> `
    /tr http://timestamp.digicert.com /td sha256 /fd sha256 `
    "build/distributions/ReactionBot-Setup-0.0.2.exe"
```

Inno Setup의 `SignTool` 디렉티브로 빌드 시 자동 서명도 가능.

### 10.8 트러블슈팅

| 증상 | 원인 / 해결 |
|---|---|
| `Inno Setup 6이 설치되어 있지 않습니다` | `winget install JRSoftware.InnoSetup` 후 재시도 |
| 인스톨러 빌드 성공했는데 SmartScreen이 막음 | 미서명 상태. 사용자에게 "자세히 → 실행" 안내. 또는 §10.7 서명 |
| 다른 PC에서 인스톨러 실행했는데 pip install이 실패 | `%APPDATA%\ReactionBot\install.log` 확인. 사내 네트워크에서 pip가 막혔을 가능성 (proxy 설정 필요) |
| 인스톨러 안 jar 파일을 못 찾음 | `bootJar` 산출물 이름과 `installer.iss` 의 Source 경로 불일치. 버전 변경 시 §10.4 확인 |

---

## 11. 고급 기능

### 능동 발문 (Idle Trigger) — 2단계 ramping
스트리머가 한참 조용하면 봇이 먼저 말을 겁니다. 침묵 시간에 따라 두 단계로 격상:

| 단계 | 트리거 | 톤 | 사용 프롬프트 |
|---|---|---|---|
| **LIGHT** | 유저 침묵 ≥ `light-threshold-ms` | 가벼운 한 마디 ("뭐 해?", "지루해~") | [CharacterConfig](src/main/java/com/jhkim/reactionbot/config/CharacterConfig.java)의 `DEFAULT_LIGHT_PROMPT` 또는 yml 오버라이드 |
| **TOPIC** | 유저 침묵 ≥ `topic-threshold-ms` | 화면 보고 새 화제 던지기 | `DEFAULT_TOPIC_PROMPT` 또는 yml 오버라이드 |

> **호명-전용 모드와 같이 쓰면 좋음.** `speech.respond-only-when-addressed: true` + `idle-trigger.enabled: true` 조합이면 "호명할 때만 응답 + 너무 조용하면 봇이 먼저 말 걺"이라 잡담 봇이 진짜 옆에 있는 느낌. 두 옵션은 서로 독립적으로 동작함.

[application.yml](src/main/resources/application.yml):
```yaml
reaction-bot:
  idle-trigger:
    enabled: true
    light-threshold-ms: 60000       # 1분 조용 → LIGHT
    topic-threshold-ms: 180000      # 3분 조용 → TOPIC 격상. 0이면 TOPIC 비활성(LIGHT만 동작).
    min-since-bot-ms: 60000         # 봇도 1분 이상 안 말했어야
    check-interval-ms: 20000        # 20초마다 체크
    light-prompt-template: ""       # 빈 값이면 내장 디폴트 사용. {name}/{streamer} 치환됨.
    topic-prompt-template: ""
```

**중요 — idle은 캐릭터 프롬프트(character.yml)를 사용하지 않습니다.** 평소 페르소나는 "반응"에 최적화되어 idle에 그대로 쓰면 너무 무겁고 진지해지는 걸 막기 위해 분리. 톤이 마음에 안 들면 `light-prompt-template` / `topic-prompt-template`에 짧은 캐릭터 묘사 + 상황 지시를 직접 적으면 됩니다. 끄려면 `enabled: false`.

### Multimodal 모드 (vision 자동 판단)
[application.yml](src/main/resources/application.yml)의 `llm.multimodal-mode`로 화면 캡처 정책 제어. **triage 단계가 있는 provider에서만 동작** — `anthropic`, `gemini`, 그리고 `claude-cli`(단 `claude-cli.triage-enabled: true`일 때만). triage가 없는 provider(`ollama`, 또는 `claude-cli`의 triage-enabled=false)는 이 설정을 무시하고 자기 vision 설정을 따름:

| 값 | 동작 |
|---|---|
| `always` | 매 발화마다 캡처. 안전하고 단순 |
| `never` | 캡처 안 함. 텍스트 잡담 봇으로 운용할 때 |
| `ai-decide` (기본) | triage 단계에서 LLM이 vision 필요 여부 판단. 화면 의존 발화만 캡처 → 텍스트 잡담·호명 응답에선 캡처 비용/지연 절감 |

`ai-decide`는 triage 토큰을 약간 더 쓰는 대신(SPEAK → SPEAK_VISION/SPEAK_TEXT 둘 중 하나) 본 호출의 이미지 토큰을 절약. 자주 호명되는 잡담형 사용 패턴에서 효과 큼. `claude-cli`의 경우 SPEAK_TEXT면 임시 PNG 저장 + Read tool 호출 자체를 건너뛰어 지연도 줄어듦.

### Pokemon 지식 보강 (PokeAPI 컨텍스트)
스트리머 발화에 한국어 포켓몬 이름(예: "피카츄", "메타몽")이 감지되면 자동으로 [PokeAPI](https://pokeapi.co)에서 타입/특성/종족값을 받아와 LLM 호출에 컨텍스트로 주입합니다. 봇이 더 정확한 멘트를 할 수 있게 됨.

> Claude API에는 MCP가 직접 연동되지 않습니다 (MCP는 Claude Desktop 전용). 여기선 **사전 컨텍스트 주입** 방식 — 한 번의 LLM 호출에 정보가 미리 들어가므로 tool use 루프 없이도 빠르게 동작합니다.

[application.yml](src/main/resources/application.yml):
```yaml
reaction-bot:
  pokemon:
    enabled: true
    pokeapi-base: "https://pokeapi.co/api/v2"
    cache-ttl-sec: 86400            # 한 번 조회한 포켓몬은 하루 캐싱
```

**감지되는 이름 추가**: [src/main/resources/pokemon-ko-en.json](src/main/resources/pokemon-ko-en.json) 에 `"한국어이름": "english-name"` 한 줄 추가하면 됨. PokeAPI 영문 이름은 https://pokeapi.co/api/v2/pokemon 에서 확인 가능.

비활성화: `enabled: false`.

## 12. 다음 단계 아이디어

- VTube Studio + Live2D로 봇 시각화
- 유튜브 채팅 연동 (시청자 멘션도 듣고 반응)
- 음성 감정 다양화 (ElevenLabs 같은 유료 TTS로 교체)

---

## License

[MIT License](LICENSE) — Copyright (c) 2026 김주현
