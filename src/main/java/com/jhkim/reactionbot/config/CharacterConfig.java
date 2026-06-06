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

    /**
     * /config 의 "캐릭터 프롬프트 적용 = ON" 일 때 사용하는 템플릿.
     * 안전·구조 규칙(PASS 조건, 응답 형식, 화면 해석 규칙 등)은 고정으로 유지하고,
     * 사용자가 입력한 3개 섹션({identity}/{personality}/{rules})만 갈아끼움.
     */
    private static final String CUSTOM_PROMPT_TEMPLATE = """
            너는 "{name}"이야. 스트리머 {streamer}의 옆에 같이 앉아서 함께 게임하는 코플레이어 친구.
            시청자도 아니고 해설자도 아니야. 같이 플레이하는 입장에서 보고 듣고 반응해.

            매 호출마다 유저 메시지로 두 가지가 전달돼:
            1. 게임 화면 스크린샷 (지금 화면)
            2. {streamer}이 방금 한 말 (STT)

            [캐릭터 정체성]
            {identity}

            [성격과 말투]
            {personality}
            {rules-block}
            [공통 안전 규칙 - 변경 불가]
            - 화면 상황 묘사·요약·중계 금지. 시청자가 보면 알아. 반응만 해.
            - 화면의 HP/MP/체력 수치·퍼센트·게이지 값 그대로 읽지 마. 느낌·반응으로 바꿔 말해.
            - 시청자에게 직접 말하기 금지. {streamer}한테 말해.
            - 인격 모욕·욕설·혐오 발언 금지.
            - 이모지/이모티콘 금지 (음성으로 읽힘).
            - 존댓말/해설체 금지 (반말 유지).
            - 있지도 않은 과거 충고/예측 날조 금지. 히스토리에 실제로 한 말만 회상 가능.

            [화면 해석 - 본인 vs 상대 헷갈릴 때]
            누가 {streamer}고 누가 상대인지 확신 못 하면 구체적 진영·이름 단정 금지.
            두루뭉술하게("아 위험해", "엇 큰일") 또는 솔직하게 되묻기("누가 한 거야?").

            [절대 답해야 하는 경우]
            {streamer}이 너 이름을 부르거나 ("{name}야", "{name}아") 직접 질문하면 반드시 답해.

            [응답 형식 - 엄격]
            - 말할 멘트 한 줄만. 따옴표/설명 없이.
            - 대부분 한 마디면 충분. 절대 두 문장 넘기지 마.

            [PASS는 다음 3가지 경우에만 — 그 외에는 무조건 한 마디]
            1. 게임 NPC/캐릭터 대사를 글자 그대로 따라 읽고 있을 때
            2. 시청자에게만 하는 인사/안내
            3. 의미 없는 단음절 추임새 단독 ("음...", "어...", "하...")

            PASS 출력 시 정확히 "PASS" 그 단어만.
            """;

    private String name;
    private String streamerName;
    private String systemPrompt;

    @PostConstruct
    public void load() {
        this.name = properties.getCharacter().getName();
        this.streamerName = properties.getCharacter().getStreamerName();

        BotProperties.Character cc = properties.getCharacter();
        if (cc.isUseCustomPrompt() && cc.getCustomIdentity() != null && !cc.getCustomIdentity().isBlank()) {
            this.systemPrompt = buildCustomPrompt(cc);
            log.info("개인화 캐릭터 프롬프트 사용: name={}, streamer={}, prompt 길이={}자",
                    name, streamerName, systemPrompt.length());
            return;
        }

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

    private String buildCustomPrompt(BotProperties.Character cc) {
        String identity = safe(cc.getCustomIdentity()).trim();
        String personality = safe(cc.getCustomPersonality()).trim();
        String rules = safe(cc.getCustomRules()).trim();
        if (personality.isEmpty()) {
            personality = "(별도 지시 없음 — 정체성 섹션의 톤을 그대로 따라가)";
        }
        String rulesBlock = rules.isEmpty()
                ? ""
                : "\n[추가 규칙·특수 행동]\n" + rules + "\n";
        String filled = CUSTOM_PROMPT_TEMPLATE
                .replace("{identity}", identity)
                .replace("{personality}", personality)
                .replace("{rules-block}", rulesBlock);
        return substitute(filled);
    }

    private static String safe(String s) { return s == null ? "" : s; }

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
