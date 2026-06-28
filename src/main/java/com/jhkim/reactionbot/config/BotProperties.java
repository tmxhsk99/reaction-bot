package com.jhkim.reactionbot.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "reaction-bot")
public class BotProperties {

    // 앱 동작 모드. /config UI 최상단에서 선택.
    //   "reaction-bot"     : 기존 리액션 봇 (STT → LLM → TTS, 화면 캡처 보조)
    //   "screen-translate" : 화면 번역 모드 (화면 OCR/번역 → /translate UI + TTS)
    private String mode = "reaction-bot";

    public boolean isScreenTranslateMode() { return "screen-translate".equalsIgnoreCase(mode); }
    public boolean isReactionBotMode() { return !isScreenTranslateMode(); }

    private Character character = new Character();
    private Llm llm = new Llm();
    private Anthropic anthropic = new Anthropic();
    private Gemini gemini = new Gemini();
    private OpenAi openai = new OpenAi();
    private Ollama ollama = new Ollama();
    private ClaudeCli claudeCli = new ClaudeCli();
    private CodexCli codexCli = new CodexCli();
    private History history = new History();
    private Tts tts = new Tts();
    private Stt stt = new Stt();
    private Screen screen = new Screen();
    private Speech speech = new Speech();
    private IdleTrigger idleTrigger = new IdleTrigger();
    private Pokemon pokemon = new Pokemon();
    private ScreenTranslate screenTranslate = new ScreenTranslate();

    @Getter @Setter
    public static class Character {
        private String name;
        private String streamerName;
        // 개인화 캐릭터 프롬프트 사용 여부. true면 아래 custom-* 필드로 character.yml 본문을 대체.
        // 기본은 false (classpath:character.yml 그대로 사용).
        private boolean useCustomPrompt = false;
        // 사용자가 정의하는 캐릭터 정체성/컨셉. 권장 200~400자.
        // 예: "도도하고 자신만만한 츤데레. 자기 의견 강하게 말함. 진짜 위급할 땐 챙겨주지만 인정 안 함."
        private String customIdentity = "";
        // 사용자가 정의하는 성격/말투. 권장 400~800자.
        // 예: "반말, 1~2문장. 까칠하게 디스함. 명령조 OK. 자랑·비교 OK. 이모지 금지."
        private String customPersonality = "";
        // 사용자가 정의하는 추가 규칙/특수 행동. 권장 0~300자. 비워둬도 됨.
        // 예: "포켓몬 얘기 나오면 종족값/타입 상성 자연스럽게 활용. 격투 게임은 콤보 칭찬."
        private String customRules = "";
    }

    @Getter @Setter
    public static class Llm {
        // "anthropic" | "gemini" | "openai" | "ollama" | "claude-cli" | "codex-cli" — 어느 provider를 활성화할지
        private String provider = "anthropic";
        // 비전(화면 캡처) 사용 정책. triage를 지원하는 provider(anthropic/gemini)에서만 의미 있음.
        // single-call provider(ollama/claude-cli)는 각 provider 설정(ollama.vision 등)을 따름.
        //   "always"    : 매 호출마다 캡처 (기본, 기존 동작 유지)
        //   "never"     : 캡처 안 함 (텍스트 전용)
        //   "ai-decide" : triage 단계에서 LLM이 vision 필요 여부 판단. 화면 의존 발화만 캡처 → 캡처 비용/지연 절감.
        private String multimodalMode = "always";
    }

    @Getter @Setter
    public static class Anthropic {
        private String apiKey;
        private String model;            // 코멘트 생성용 (Sonnet)
        private String triageModel;      // PASS/SPEAK 1차 판단용 (Haiku) - 비용 절감
        private int maxTokens;
    }

    @Getter @Setter
    public static class Gemini {
        private String apiKey;
        private String model;            // 코멘트 생성용 (Flash)
        private String triageModel;      // PASS/SPEAK 1차 판단용 (Flash-Lite)
        private int maxTokens;
    }

    /**
     * OpenAI API 백엔드 (토큰 종량 과금). ClaudeService/GeminiService와 동일한 2단계 구조.
     * 인증: apiKey 비어있으면 OPENAI_API_KEY 환경변수 사용.
     */
    @Getter @Setter
    public static class OpenAi {
        private String apiKey;           // 빈 값이면 OPENAI_API_KEY 환경변수
        private String model;            // 코멘트 생성용 (vision 가능 모델. 예: gpt-4o-mini)
        private String triageModel;      // PASS/SPEAK 1차 판단용 (저렴한 모델)
        private int maxTokens;
    }

    @Getter @Setter
    public static class Ollama {
        private String baseUrl = "http://localhost:11434";   // Ollama 서버 URL
        private String model = "qwen3-vl:8b";                // 모델 태그 (예: qwen3, qwen3-vl:8b, qwen2.5vl:7b)
        private int maxTokens = 280;                         // num_predict
        private Integer numCtx;                              // null이면 모델 기본값(보통 4096). VL 모델 OOM 방지용으로 명시 권장
        private Double temperature;                          // null이면 모델 기본값 사용 (~0.7). 0.9 권장 (다양성↑)
        private Double topP;                                 // null이면 모델 기본값. 0.95 권장
        private Double repeatPenalty;                        // null이면 Ollama 기본(1.1). 1.3 권장. 단어 토큰 루프(degeneration) 차단
        private Integer repeatLastN;                         // null이면 Ollama 기본(64). 128 권장. 더 넓은 윈도우에서 반복 검사
        private int requestTimeoutSec = 180;                 // 응답 타임아웃. 콜드 로드 포함하면 첫 호출 60s+ 걸릴 수 있어 여유 둠
        private String keepAlive = "1h";                     // 모델을 메모리에 유지하는 시간 (예: "10m", "1h", "-1"=영구). 방송 중 콜드 로드 방지
        private boolean think = false;                       // qwen3 thinking 모드 (false=꺼서 속도↑)
        private boolean vision = true;                       // true=화면 캡처+이미지 전송 (qwen3-vl/qwen2.5vl 등 VL 모델용)
        private boolean assertive = true;                    // true=로컬 전용 적극성 nudge 자동 주입 (PASS 줄이고 더 말함)
        private boolean warmupOnStart = true;                // true=앱 기동 시 더미 호출로 모델 미리 로딩 (첫 발화 즉시 응답)
        // ollama 백엔드 전용 추가 시스템 지침. 한국어 강제·이모지 금지·화면 텍스트 파싱 규칙 등.
        // qwen 계열이 한자/영문으로 빠지는 걸 막기 위해 필요. 빈 값이면 미주입.
        private String extraSystemPrompt = "";
    }

    /**
     * Claude Code CLI 백엔드 (구독 한도 내에서 호출, API 토큰 비용 0).
     * subprocess 오버헤드가 크므로 1회 호출 모드 + 히스토리는 매번 프롬프트에 합성.
     */
    @Getter @Setter
    public static class ClaudeCli {
        // CLI 실행파일. executableSearchDir로 자동 탐색이 성공하면 이 값은 무시됨.
        // 자동 탐색 실패 시 fallback. PATH에 있으면 "claude" / "claude.cmd", 없으면 절대경로.
        private String executable = "claude";
        // 버전 폴더 자동 탐색 디렉토리. 빈 값이면 OS별 기본 후보 사용.
        //  Windows 기본: %APPDATA%\Claude\claude-code  (네이티브 인스톨러 설치 위치)
        // 이 디렉토리 안의 하위 폴더(예: "2.1.149")들 중 가장 높은 SemVer를 골라
        // <searchDir>/<버전>/claude.exe 를 실제 executable로 사용. 업데이트 자동 추종.
        // 자동 탐색이 안 맞는 환경(예: npm 글로벌 설치)에선 이 값을 빈 문자열로 두고
        // executable 에 직접 경로를 넣을 것.
        private String executableSearchDir = "";
        // CLI를 실행할 작업 디렉토리. 빈 값이면 봇 프로세스의 cwd 사용.
        // Claude Code는 cwd 기준으로 .claude/settings.json·CLAUDE.md를 읽으므로,
        // 봇 프로젝트 컨텍스트가 섞이는 게 싫으면 빈 디렉토리(예: tts-output 같은)로 지정.
        private String workingDir = "";
        // 모델 지정. 빈 값이면 CLI 기본값(보통 Sonnet).
        // 리액션 봇처럼 가볍게 굴릴 거면 "claude-haiku-4-5-20251001" 권장.
        private String model = "";
        // 한 번의 호출 최대 대기 시간. CLI 콜드 스타트 + 추론 포함.
        private int timeoutSec = 30;
        // 임시 스크린샷 저장 디렉토리. 빈 값이면 OS 임시 디렉토리(java.io.tmpdir).
        // 호출 끝나면 항상 삭제.
        private String tempImageDir = "";
        // true면 stdin으로 프롬프트 전달, false면 -p 인자로 전달.
        // 긴 히스토리·이미지 경로 포함 시 stdin 권장(ARG_MAX 회피).
        private boolean useStdinPrompt = true;
        // CLI에 허용할 도구. 빈 값이면 미지정(CLI 기본).
        // 리액션 봇은 도구 거의 안 써도 되니 "Read" 정도면 충분(이미지 읽기용).
        private String allowedTools = "Read";
        // false면 캐릭터 프롬프트만으로 단순 호출. true면 ollama처럼 적극성 nudge 추가.
        private boolean assertive = true;
        // claude-cli 백엔드 전용 추가 시스템 지침. character.yml 뒤에 append.
        // 예) 한국어 강제·이모지 금지·줄 수 제한 등. 빈 값이면 미주입.
        private String extraSystemPrompt = "";
        // true면 매 발화에 triage CLI 호출(PASS/SPEAK 1차 판단) 추가. 본 호출 전에 1번 더 도는 셈.
        // 트레이드오프:
        //  + 명백한 PASS 케이스에서 스크린샷 저장/Read tool 호출/큰 모델 추론 비용 절약
        //  + multimodal-mode=ai-decide와 조합하면 SPEAK_TEXT일 때 캡처 자체 생략
        //  - 매 호출마다 subprocess 콜드스타트(보통 2~4초) 2번 → 본 호출까지 지연 추가
        //    (구독 한도 안에서는 호출 횟수 자체에 비용은 없음. 시간 비용만 늘어남)
        // 권장: 호명-전용 모드처럼 PASS 빈도가 높은 사용 패턴에서만 ON.
        private boolean triageEnabled = false;
        // triage용 모델. 빈 값이면 main model(executable=cfg.model)과 동일.
        // Haiku가 5~10배 빠르므로 triage용으로 추천. 예: "claude-haiku-4-5-20251001"
        private String triageModel = "claude-haiku-4-5";
        // triage CLI 호출 타임아웃 (s). 본 호출보다 짧게.
        private int triageTimeoutSec = 15;
        // TTS로 흘러갈 최대 문장 수. sanitizeOutput()에서 적용.
        //  > 0  : 해당 문장 수까지만 유지 (예: 2 = 2문장까지)
        //  ≤ 0  : 무제한. CLI가 뱉은 raw 응답을 그대로 통과시킴(라벨/따옴표 정제는 여전히 수행).
        // 캐릭터 룰(1~2문장)에 맞추려면 2 권장. raw 그대로 보고 싶으면 0.
        private int maxSentences = 2;
    }

    /**
     * Codex CLI 백엔드 (OpenAI). subprocess로 `codex exec` 호출.
     * 인증: apiKey 설정 시 CODEX_API_KEY 환경변수로 종량 과금. 비어있으면 `codex login`으로
     *       저장된 ChatGPT 구독 인증 재사용(토큰 비용 0).
     *
     * Claude Code와 다른 점:
     *  - --system-prompt(기본 프롬프트 통째 교체) 플래그가 없음 → 캐릭터 페르소나를 프롬프트 본문 앞에 주입.
     *  - 이미지는 -i 플래그로 직접 첨부(Read 도구 불필요).
     *  - 최종 메시지는 -o <파일>로 캡처.
     */
    @Getter @Setter
    public static class CodexCli {
        // CLI 실행파일. PATH에 있으면 "codex", 없으면 절대경로. 환경변수 CODEX_CLI_EXEC로 덮어쓰기 가능.
        private String executable = "codex";
        // API 키. 설정 시 CODEX_API_KEY 환경변수로 전달(종량 과금). 빈 값이면 저장된 codex login(구독) 사용.
        private String apiKey = "";
        // 생성 모델. 빈 값이면 codex 설정 기본값. 예: "gpt-5-codex", "o4-mini"
        private String model = "";
        // triage용 모델. 빈 값이면 main model과 동일.
        private String triageModel = "";
        // codex 추론 강도. "minimal" | "low" | "medium" | "high". 빈 값이면 미지정(codex 기본).
        // 실시간 봇은 응답 속도가 중요 → "low" 권장. -c model_reasoning_effort=<값> 로 전달.
        private String reasoningEffort = "low";
        // codex exec를 실행할 작업 디렉토리(--cd). 빈 값이면 봇 cwd.
        // codex가 AGENTS.md를 읽으므로 봇 프로젝트 컨텍스트 섞임이 싫으면 빈 디렉토리 지정 권장.
        private String workingDir = "";
        // git 저장소 밖에서도 실행 허용(--skip-git-repo-check). 빈 작업 디렉토리 쓸 때 필요.
        private boolean skipGitRepoCheck = true;
        // ~/.codex/config.toml 로드 건너뛰기(--ignore-user-config). 인증은 그대로 사용.
        private boolean ignoreUserConfig = false;
        // 한 번 호출 최대 대기(초). 콜드 스타트 + 추론 포함.
        private int timeoutSec = 40;
        // 임시 스크린샷 저장 디렉토리. 빈 값이면 OS 임시 디렉토리. 호출 끝나면 항상 삭제.
        private String tempImageDir = "";
        // true면 ollama처럼 적극성 nudge 추가.
        private boolean assertive = true;
        // codex 백엔드 전용 추가 시스템 지침(페르소나 블록 뒤 append). 한국어 강제·이모지 금지·줄 수 제한 등.
        private String extraSystemPrompt = "";
        // true면 매 발화에 triage CLI 호출 추가(PASS/SPEAK 1차 판단). PASS 빈도 높은 패턴에서만 권장.
        private boolean triageEnabled = false;
        // triage CLI 호출 타임아웃(초). 본 호출보다 짧게.
        private int triageTimeoutSec = 20;
    }

    @Getter @Setter
    public static class History {
        private int maxTurns;
    }

    @Getter @Setter
    public static class Tts {
        private String provider = "edge";   // 현재는 "edge"만 지원
        private String pythonExecutable;
        private String voice;
        private String rate;
        private String pitch;
        private String outputDir;
        private boolean cleanupOnStartup;   // true면 서버 기동 시 잔재 mp3 일괄 삭제
        private boolean cleanupOnShutdown;  // true면 서버 종료 시 mp3 일괄 삭제
    }

    @Getter @Setter
    public static class Stt {
        private boolean autoStart;
        private String pythonExecutable;
        private String scriptPath;
        private String model;
        private String language;
        private String computeType;
        private String device;
        private int vadAggressiveness;
        private Integer deviceIndex;
        private String serverUrl;
        private int beamSize;
        private String initialPrompt;
        private Double minAvgLogprob;   // null이면 stt_worker.py 디폴트(-1.0) 사용
    }

    @Getter @Setter
    public static class Screen {
        private String source;          // "obs" 또는 "robot"
        private int monitorIndex;       // source=robot일 때만 사용
        private Obs obs = new Obs();    // source=obs일 때만 사용
    }

    @Getter @Setter
    public static class Obs {
        private String host;
        private int port;
        private String password;        // 빈 문자열이면 인증 없이 연결
        private String sourceName;      // 빈 문자열이면 현재 프로그램 씬 자동 사용
        private int timeoutMs;
    }

    @Getter @Setter
    public static class Speech {
        private boolean ignoreDuringSpeech;
        private int gracePeriodMs;
        private int minTextLength;
        private int cooldownMs;
        private java.util.List<String> fillerPatterns = new java.util.ArrayList<>();
        private int nudgeAfterPassCount;    // 연속 N번 PASS 시 다음 호출에 "응답하라" 힌트 주입. 0이면 비활성
        // true면 봇 이름 호명("리봇아!") 발화에만 응답. 일반 잡담엔 LLM 호출도 안 함 → 완전 수동 모드.
        // false면 모든 발화를 LLM이 보고 PASS/SPEAK 판단 (기본 동작).
        private boolean respondOnlyWhenAddressed = false;
        // TTS 직전에 LLM 응답 텍스트의 비속어를 마스킹/PASS 처리. 시스템 프롬프트의 욕설 금지가
        // 지켜지지 않을 때의 안전망. 비활성화 시 LLM 응답이 그대로 흘러감.
        private ProfanityFilter profanityFilter = new ProfanityFilter();
    }

    /**
     * LLM 출력 비속어 필터 토글. application.yml 의 speech.profanity-filter 와 매핑.
     *
     *  - mode=mask : 매핑된 단어는 치환해서 발화. forbid-patterns 매칭은 PASS.
     *  - mode=pass : 매핑/패턴 어느 하나라도 걸리면 무조건 PASS.
     *
     * 실제 매핑 사전(단어 ↔ 대체어, forbid 패턴)은 별도 파일로 관리:
     *  - classpath:profanity-mappings.yml (디폴트, jar 안)
     *  - cwd ./profanity-mappings.yml 또는 mappings-file 경로 (있으면 단독 source).
     *  - /config UI 에서 편집 시 cwd 파일에 dump.
     */
    @Getter @Setter
    public static class ProfanityFilter {
        private boolean enabled = false;
        private String mode = "mask";        // "mask" | "pass"
        private boolean wholeWord = false;
        // 사용자 매핑 파일 경로. 빈 값이면 cwd "./profanity-mappings.yml" 사용.
        private String mappingsFile = "";
    }

    @Getter @Setter
    public static class IdleTrigger {
        private boolean enabled;
        // 2단계 ramping. light 먼저 발동 → 그 후에도 계속 조용하면 topic 단계로 격상.
        // light : 가볍게 한 마디 ("뭐 해?", "지루해~")
        // topic : 화면 보고 새 화제 던지기 ("아 그 아이템 처음 봐", "이거 어떻게 깰 거야?")
        private int lightThresholdMs = 60000;        // 유저 침묵 N ms 시 light 단계 발동
        private int topicThresholdMs = 180000;       // 유저 침묵 N ms 시 topic 단계 격상. 0/음수면 비활성(light만)
        private int minSinceBotMs = 60000;           // 봇 마지막 발화 이후 N ms 이상 지났을 때만
        private int checkIntervalMs = 20000;         // 스케줄러 체크 주기
        // idle 전용 시스템 프롬프트. character.yml 캐릭터 프롬프트와 분리.
        // 평소 페르소나는 "반응"에 최적화되어 idle에서 그대로 쓰면 너무 길고 진지해지는 걸 막기 위함.
        // {name} / {streamer} placeholder 치환됨. 빈 값이면 내장 디폴트 사용.
        private String lightPromptTemplate = "";
        private String topicPromptTemplate = "";

        public enum Stage { LIGHT, TOPIC }
    }

    @Getter @Setter
    public static class Pokemon {
        private boolean enabled;
        private String pokeapiBase;         // PokeAPI 베이스 URL
        private int cacheTtlSec;
        private Overlay overlay = new Overlay();
    }

    /**
     * 화면 번역 모드 설정. reaction-bot.mode=screen-translate 일 때만 의미.
     * 자동 모드: 주기 캡처 → dHash prefilter → 변화 감지 시 triage LLM(대화 여부) → 메인 LLM 번역 → TTS + /translate UI.
     * 수동 모드: /translate UI의 번역 버튼 클릭으로 단발 번역 (대상 언어면 무조건 번역).
     */
    @Getter @Setter
    public static class ScreenTranslate {
        // 소스 언어 (다중 가능). ISO 639-1 코드. "ja", "en", "zh", "ja en" 형식의 토큰 모두 인식.
        // 대상 언어(target-lang)는 자동 스킵.
        private java.util.List<String> sourceLangs = new java.util.ArrayList<>(java.util.List.of("ja"));
        // 번역 결과 언어. 기본 한국어.
        private String targetLang = "ko";
        // true=자동 모드(주기 캡처), false=수동 모드(/translate 버튼만 트리거).
        private boolean autoMode = true;
        // 자동 모드 캡처 주기 (ms). 너무 짧으면 LLM 비용 폭증. 권장 800~2000.
        private int intervalMs = 1000;
        // true면 자동 모드에서 "대화창/캐릭터 대사로 보이는 텍스트만" 번역.
        // false면 UI 메뉴/시스템 메시지도 다 번역 시도.
        private boolean dialogueOnly = true;
        // 번역 결과 TTS 발화 여부. 기존 reaction-bot.tts 설정(voice/rate/pitch) 재사용.
        private boolean ttsEnabled = true;
        // 한 페이지에 표시할 줄 수. 더 길면 ▶ 버튼으로 페이징.
        private int linesPerPage = 2;
        // 캡처 범위 모드. "fullscreen" | "region"
        //   fullscreen : 전체 화면을 LLM 에 넘김. 대화창 위치를 LLM이 판단해야 함.
        //   region     : crop-region 좌표(아래)만 잘라서 넘김. 정확도/속도/비용 모두 우위.
        private String captureMode = "fullscreen";
        // 사용자 지정 대화창 영역. "x,y,w,h" 정규화 좌표(0~1). capture-mode=region 일 때만 사용.
        // /config UI의 "대화창 영역 지정" 버튼이 스크린샷 위에서 드래그한 결과를 여기에 저장.
        private String cropRegion = "";
        // triage(변화 감지·대화 판단) 단계의 LLM provider 오버라이드. 빈 값=메인 provider 재사용.
        // 예: 메인은 claude-cli 쓰면서 triage만 gemini로 분리해 비용 절감.
        // (현재는 메인 provider 재사용만 구현. 후속에서 분리 시 사용.)
        private String triageProvider = "";
        // 화면번역 히스토리 디렉토리. 빈 값이면 cwd 의 "./translation-history".
        // JSONL (translation-history/YYYY-MM-DD.jsonl) + aliases.json 저장.
        private String historyDir = "";
        // 화면이 "단색(검정/흰/로딩)" 으로 판정되는 휘도 stddev 임계.
        //   stddev < 이 값  → blank 으로 보고 LLM 호출 자체 스킵
        //   기본 8.0 (reaction-bot 모드와 동일). OBS 검은 씬·로딩 화면 컷.
        //   문제 진단: /translate/debug 에서 lumaMean/lumaStddev 확인 후 조정.
        //   값이 클수록 "단색"으로 판정되기 쉬워 더 자주 스킵. 0 으로 두면 사실상 비활성.
        private double blankLumaStddev = 8.0;
        // dHash hamming distance 임계. 이 이하면 "같은 화면"으로 간주.
        // 기본 10 — 안티앨리어싱/압축 노이즈를 흡수하면서도 자막 변화는 감지하는 권장값.
        // 너무 크면 다른 자막을 같다고 오판, 너무 작으면 미세 변화에도 LLM 호출.
        private int hashStabilityThreshold = 10;
        // 2-프레임 안정성 체크. true 면 직전 프레임과 비슷할 때(=화면이 settle)만 LLM 호출.
        // 캐릭터 이동/카메라 워크처럼 매 프레임이 다른 상태에선 LLM 호출 자체 스킵 → 비용 절감.
        // false 면 hash 변화 즉시 호출 (구버전 동작).
        private boolean requireFrameStability = true;
        // 번역 결과 유사도 dedup 임계 (0~1). 직전 source/translated 와 ratio > 이 값이면 중복 처리.
        // 1.0 = 완전 일치만 dedup, 0.85 = 약간 다르면(부호/공백 차이) 같이 dedup.
        // 0 으로 두면 사실상 dedup 비활성.
        private double translationDedupSimilarity = 0.85;
        // 최근 번역을 메모리에 보관할 개수 (UI 재생 버튼용). 히스토리 파일과 무관.
        private int recentBufferSize = 10;
        // 화면 번역용 LLM 전달 이미지 가로폭(px). reaction-bot 의 672 와 별개.
        //   672 : reaction-bot 기본 (vision 토큰 절감용. 큰 이벤트 인식엔 충분하지만 자막 글자엔 작음)
        //   1280 : 화면 번역 권장 (자막 글자 가독성 ↑, vision 토큰은 약 2배)
        //   1568 : Anthropic 권장 최대 (Claude 가 자동 다운샘플 안 함). 가장 정확하지만 토큰 최대
        // 원본보다 크면 리사이즈 안 함 (확대 안 함).
        private int targetWidth = 1280;
    }

    /**
     * 스크린샷에서 포켓몬을 인식해 /pokemon-overlay HTML 화면에 띄우는 add-on.
     * 발화 흐름과 분리된 별개 기능. enabled=false 면 비활성(스케줄러/엔드포인트도 no-op).
     *
     * 트리거 모드:
     *   - auto              : refresh-interval-ms 주기로 자동 분석 (방송 중 켜놓기)
     *   - manual            : /pokemon-overlay 우측 분석 버튼으로만 트리거
     *   - speech-precapture : 발화 프리캡처 시점에 함께 분석 (발화 있을 때만 갱신)
     *
     * 세대(generation): 1~9. PokéAPI past_values를 활용해 해당 세대 시점의 종족값/타입을 표시.
     * max-pokemon: 한 화면에 보여줄 카드 수 상한 (2=싱글배틀, 4=더블배틀).
     */
    @Getter @Setter
    public static class Overlay {
        private boolean enabled = false;
        // 1~9 (9세대까지). 이후 세대 추가될 경우 그대로 정수 입력 허용.
        private int generation = 9;
        // "auto" | "manual" | "speech-precapture"
        private String mode = "manual";
        // auto 모드일 때 폴링 주기. 너무 짧으면 LLM 비용 폭증.
        private int refreshIntervalMs = 10000;
        // 보여줄 포켓몬 카드 최대 개수 (2 / 4).
        private int maxPokemon = 2;
        // 기술 배치 추측 표시 여부. true면 LLM에게 4개 기술 추측까지 요청. 응답 길어지고 느려짐.
        private boolean inferMoves = false;
        // 화면 전체에서 포켓몬 영역만 잘라보고 싶을 때 사용. 빈 값이면 전체 캡처 그대로 사용.
        // "x,y,w,h" 정규화 좌표(0~1). 예: "0,0.6,1,0.4" (하단 40% 사용).
        private String cropRegion = "";
        // 화면 내 무시할 영역(검정 박스로 마스킹). 봇 자체 오버레이가 좌측 상단에 떠있을 때
        // 그 영역을 가려서 LLM이 자기 카드를 분석 대상으로 잡는 사고 방지.
        // "x,y,w,h" 정규화 좌표(0~1). cropRegion 적용 후 그 영역 내 좌표로 마스킹.
        // 여러 영역 지정은 ";" 로 구분. 예: "0,0,0.25,0.5;0,0.5,0.25,0.3"
        private String ignoreRegion = "";
    }
}
