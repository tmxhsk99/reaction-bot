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
    private OllamaDual ollamaDual = new OllamaDual();
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
     * 2-스테이지 로컬 파이프라인. 비전 모델이 화면을 한국어 설명으로 변환 → 텍스트 모델이 발화+설명으로 반응.
     * 단일 VL 모델 대비 장점:
     *  - 캐릭터 프롬프트가 비전 호출엔 안 들어가 VL 호출이 짧고 빠름
     *  - 텍스트 모델은 이미지 토큰 없이 작은 컨텍스트로 디코드 → 응답 속도↑
     *  - thinking 강제 모델(qwen3-vl 등) 회피
     */
    @Getter @Setter
    public static class OllamaDual {
        private String baseUrl = "http://localhost:11434";
        private int requestTimeoutSec = 180;        // 콜드 로드 포함 첫 호출 여유
        private boolean assertive = true;           // 텍스트 단계에 적극성 nudge 자동 주입
        private boolean warmupOnStart = true;       // 기동 시 두 모델 사전 로드
        private VisionModel visionModel = new VisionModel();
        private TextModel textModel = new TextModel();
    }

    @Getter @Setter
    public static class VisionModel {
        private String model = "qwen2.5vl:7b";      // 이미지 → 한국어 설명 모델
        private int maxTokens = 200;                // 묘사용이라 짧게
        private Double temperature = 0.3;           // 사실 묘사라 낮게 (다양성보다 정확도)
        private Integer numCtx = 4096;
        private String keepAlive = "1h";
        // 비전 단계용 추가 시스템 지침. 한국어 강제 + 화면 텍스트 정확 인용 룰 등. 빈 값이면 미주입.
        private String extraSystemPrompt = "";
    }

    @Getter @Setter
    public static class TextModel {
        private String model = "qwen2.5:7b";        // 화면설명+발화 → 반응 생성
        private int maxTokens = 200;
        private Double temperature = 0.9;
        private Double topP = 0.95;
        private Double repeatPenalty;               // null이면 Ollama 기본(1.1). 1.3 권장. "루기아 루기아" 류 토큰 루프 차단
        private Integer repeatLastN;                // null이면 Ollama 기본(64). 128 권장. 발화 단위 반복까지 검사
        private Integer numCtx = 4096;
        private String keepAlive = "1h";
        private boolean think = false;              // /no_think 토큰 자동 주입할지
        // 텍스트(발화) 단계용 추가 시스템 지침. 한국어 강제 + 이모지 금지 + 중복 변주 등. 빈 값이면 미주입.
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
