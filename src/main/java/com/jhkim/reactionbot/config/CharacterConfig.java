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
            this.systemPrompt = rawPrompt
                    .replace("{name}", this.name)
                    .replace("{streamer}", this.streamerName);
            log.info("캐릭터 로드 완료: name={}, streamer={}, prompt 길이={}자",
                    name, streamerName, systemPrompt.length());
        } catch (Exception e) {
            throw new IllegalStateException("character.yml 로드 실패", e);
        }
    }
}
