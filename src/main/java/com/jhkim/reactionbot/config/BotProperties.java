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

    private Character character = new Character();
    private Llm llm = new Llm();
    private Anthropic anthropic = new Anthropic();
    private Gemini gemini = new Gemini();
    private Ollama ollama = new Ollama();
    private ClaudeCli claudeCli = new ClaudeCli();
    private History history = new History();
    private Tts tts = new Tts();
    private Stt stt = new Stt();
    private Screen screen = new Screen();
    private Speech speech = new Speech();
    private IdleTrigger idleTrigger = new IdleTrigger();
    private Pokemon pokemon = new Pokemon();

    @Getter @Setter
    public static class Character {
        private String name;
        private String streamerName;
    }

    @Getter @Setter
    public static class Llm {
        // "anthropic" | "gemini" | "ollama" — 어느 provider를 활성화할지
        private String provider = "anthropic";
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
    }

    @Getter @Setter
    public static class History {
        private int maxTurns;
    }

    @Getter @Setter
    public static class Tts {
        private String provider;            // "azure" 또는 "edge"
        private String pythonExecutable;
        private String voice;
        private String rate;
        private String pitch;
        private String outputDir;
        private boolean cleanupOnStartup;   // true면 서버 기동 시 잔재 mp3 일괄 삭제
        private boolean cleanupOnShutdown;  // true면 서버 종료 시 mp3 일괄 삭제
        private Azure azure = new Azure();
    }

    @Getter @Setter
    public static class Azure {
        private String key;
        private String region;
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
    }

    @Getter @Setter
    public static class IdleTrigger {
        private boolean enabled;
        private int silenceThresholdMs;     // 유저 발화 후 N ms 침묵 시 발동
        private int minSinceBotMs;          // 봇 마지막 발화 이후 N ms 이상 지났을 때만
        private int checkIntervalMs;        // 스케줄러 체크 주기
    }

    @Getter @Setter
    public static class Pokemon {
        private boolean enabled;
        private String pokeapiBase;         // PokeAPI 베이스 URL
        private int cacheTtlSec;
    }
}
