package com.jhkim.reactionbot.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

/**
 * character.yml에서 system-prompt 템플릿을 읽고,
 * application.yml(또는 BOT_NAME/STREAMER_NAME 환경변수)의 이름으로 {name}/{streamer} placeholder를 치환.
 */
@Slf4j
@Getter
@Component
@RequiredArgsConstructor
public class CharacterConfig {

    private final BotProperties properties;

    /**
     * idle-light용 내장 디폴트. 사용자가 application.yml에서 light-prompt-template 안 채웠을 때 사용.
     * 캐릭터 본인 톤은 유지하되 짧고 가벼운 혼잣말 위주로 설계 (풀 페르소나 프롬프트를 idle에 쓰면 너무 무거움).
     */
    private static final String DEFAULT_LIGHT_PROMPT = """
            너는 "{name}"이야. 스트리머 {streamer}이 한참 조용해.
            가볍게 한 마디 던져. 혼잣말 톤 또는 가벼운 말 걸기. 반말, 1문장.
            의견·예측·놀림·딴지·자랑 다 OK. 길게 늘어놓지 말 것.
            별로 할 말 없으면 정확히 "PASS"만 출력.
            이모지/이모티콘 금지 (음성 합성됨).
            """;

    /**
     * idle-topic용 내장 디폴트. 침묵이 더 길어졌을 때 화면 기반으로 새 화제를 던지는 용도.
     */
    private static final String DEFAULT_TOPIC_PROMPT = """
            너는 "{name}"이야. 스트리머 {streamer}이 한참 조용해서 흐름이 끊겼어.
            화면을 보고 흥미로운 디테일을 하나 짚어서 새 화제를 던지거나 질문 한 마디.
            반말, 1~2문장. 너무 진지하지 말 것. 캐릭터 톤 유지.
            화면에 화제 만들 거리가 정말 없으면 정확히 "PASS"만 출력.
            이모지/이모티콘 금지.
            """;

    private String name;
    private String streamerName;
    private String systemPrompt;

    @PostConstruct
    public void load() {
        this.name = properties.getCharacter().getName();
        this.streamerName = properties.getCharacter().getStreamerName();

        try (InputStream is = new ClassPathResource("character.yml").getInputStream()) {
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(is);
            @SuppressWarnings("unchecked")
            Map<String, Object> character = (Map<String, Object>) root.get("character");
            String rawPrompt = (String) character.get("system-prompt");
            this.systemPrompt = substitute(rawPrompt);
            log.info("캐릭터 로드 완료: name={}, streamer={}, prompt 길이={}자",
                    name, streamerName, systemPrompt.length());
        } catch (Exception e) {
            throw new IllegalStateException("character.yml 로드 실패", e);
        }
    }

    /** application.yml의 idle-trigger.light-prompt-template 또는 내장 디폴트, name/streamer 치환. */
    public String resolveIdleLightPrompt() {
        String tpl = properties.getIdleTrigger().getLightPromptTemplate();
        return substitute((tpl == null || tpl.isBlank()) ? DEFAULT_LIGHT_PROMPT : tpl);
    }

    /** application.yml의 idle-trigger.topic-prompt-template 또는 내장 디폴트, name/streamer 치환. */
    public String resolveIdleTopicPrompt() {
        String tpl = properties.getIdleTrigger().getTopicPromptTemplate();
        return substitute((tpl == null || tpl.isBlank()) ? DEFAULT_TOPIC_PROMPT : tpl);
    }

    /**
     * {name}/{streamer} placeholder 치환. name/streamerName이 null이어도 NPE 안 나게 방어.
     * application.yml에 ${BOT_NAME:리봇}/${STREAMER_NAME:로크만} 기본값이 있어 보통은 non-null이지만,
     * 환경변수로 빈 값/누락 주입 같은 엣지케이스 대비.
     * 각 LLM provider가 triage 프롬프트 등의 {name}/{streamer}를 치환할 때도 사용.
     */
    public String substitute(String s) {
        if (s == null) return "";
        String n = (this.name == null) ? "" : this.name;
        String st = (this.streamerName == null) ? "" : this.streamerName;
        return s.replace("{name}", n).replace("{streamer}", st);
    }
}
