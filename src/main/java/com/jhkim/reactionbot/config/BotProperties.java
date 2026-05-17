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
    private Anthropic anthropic = new Anthropic();
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
    public static class Anthropic {
        private String apiKey;
        private String model;            // 코멘트 생성용 (Sonnet)
        private String triageModel;      // PASS/SPEAK 1차 판단용 (Haiku) - 비용 절감
        private int maxTokens;
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
