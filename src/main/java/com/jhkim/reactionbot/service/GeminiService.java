package com.jhkim.reactionbot.service;

import com.google.genai.Client;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.ThinkingConfig;
import com.jhkim.reactionbot.config.BotProperties;
import com.jhkim.reactionbot.config.CharacterConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Google Gemini 백엔드.
 *   - triage(): Flash-Lite, thinking budget 0 (가장 저렴)
 *   - generateComment(): Flash, thinking budget 0
 *
 * application.yml의 reaction-bot.llm.provider=gemini 일 때만 빈 생성.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "reaction-bot.llm", name = "provider", havingValue = "gemini")
public class GeminiService implements LlmProvider {

    private static final String TRIAGE_SYSTEM = """
            당신은 스트리밍 코멘터리 봇의 판단기입니다.
            방금 스트리머가 한 말(텍스트)만 보고, 봇이 "지금 한 마디 해도 될 상황인가" 판단하세요.
            (비용 절감을 위해 1차 판단에는 화면 이미지가 제공되지 않습니다. 텍스트만으로 보수적으로 판단.)
            답은 정확히 SPEAK 또는 PASS 둘 중 하나. 다른 글자 절대 금지.

            SPEAK 기준:
              - 게임에 의미있는 사건 (성공·실패·등장·발견·전투 등)이 있고 스트리머도 그에 대해 말함
              - 스트리머가 명확히 반응 유도 ("야 봐", "오 대박", "이거 뭐야")
              - 한참 조용했다가 의미 있는 발화

            PASS 기준 (대부분 PASS):
              - 시청자에게 안내/설명하는 중
              - 게임 NPC/캐릭터 대사 따라하기
              - 의미 없는 추임새, 헛기침
              - 단순 화면 상태 언급만 ("HP 얼마야", "다음 가야지")
              - 애매하면 PASS. 침묵이 미덕.
            """;

    /** multimodal-mode=ai-decide 전용. SPEAK를 vision 필요 여부로 둘로 쪼갬. */
    private static final String TRIAGE_SYSTEM_WITH_VISION = """
            당신은 스트리밍 코멘터리 봇의 판단기입니다.
            방금 스트리머가 한 말(텍스트)만 보고 다음 3가지 중 하나를 선택하세요.
            답은 정확히 다음 라벨 셋 중 하나. 다른 글자 절대 금지: PASS | SPEAK_VISION | SPEAK_TEXT

            PASS:
              - 시청자에게 안내/설명, NPC 대사 따라하기, 단순 추임새, 단순 화면 상태 언급
              - 애매하면 PASS

            SPEAK_VISION (화면을 봐야 답할 수 있는 경우):
              - 게임 사건에 대한 반응 ("오 대박", "이거 뭐야", "여기로 가야 되나")
              - 화면의 무언가를 지칭/질문 ("이거 어때?", "저거 뭐였지?")
              - 스트리머가 "야 봐", "이거 봐바" 류로 화면을 가리킴

            SPEAK_TEXT (화면 없이도 답할 수 있는 경우):
              - 봇 이름 호명 + 텍스트 질문 ("{name}아 너는 뭐 좋아해?", "{name} 잘 지냈어?")
              - 봇 의견·생각을 묻는 일반 잡담 ("너는 어떻게 생각해?")
              - 화면 맥락 없는 자기 얘기·회상
            """;

    private final BotProperties properties;
    private final CharacterConfig character;
    private final ConversationHistory history;
    private final PokemonContextService pokemonContext;
    private final PassCounter passCounter;

    private Client client;

    @PostConstruct
    public void init() {
        String apiKey = properties.getGemini().getApiKey();
        Client.Builder builder = Client.builder();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.apiKey(apiKey);
            log.info("Gemini 클라이언트: application.yml 키 사용");
        } else {
            log.info("Gemini 클라이언트: GOOGLE_API_KEY 환경변수 사용 (apiKey 미설정)");
        }
        this.client = builder.build();
        log.info("LLM provider=gemini. 모델: comment={}, triage={}",
                properties.getGemini().getModel(),
                properties.getGemini().getTriageModel());
    }

    @Override
    public TriageResult triage(String userText, boolean needsVisionDecision) {
        String input = (userText == null || userText.isBlank())
                ? "(자동 트리거)"
                : userText;

        String basePrompt = character.substitute(needsVisionDecision ? TRIAGE_SYSTEM_WITH_VISION : TRIAGE_SYSTEM);
        String systemPrompt = basePrompt + passCounter.buildNudge("triage");

        List<Content> contents = new ArrayList<>();
        contents.add(Content.builder()
                .role("user")
                .parts(List.of(Part.fromText(input)))
                .build());

        int maxTokens = needsVisionDecision ? 16 : 10;
        GenerateContentConfig config = GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(Part.fromText(systemPrompt)))
                .maxOutputTokens(maxTokens)
                .thinkingConfig(ThinkingConfig.builder().thinkingBudget(0).build())
                .build();

        log.debug("Triage 호출 (Gemini Flash-Lite, 텍스트 전용, vision-decide={}). 연속PASS: {}",
                needsVisionDecision, passCounter.get());
        GenerateContentResponse response = client.models.generateContent(
                properties.getGemini().getTriageModel(), contents, config);
        String raw = safeText(response).toUpperCase().replaceAll("[^A-Z_]", "");

        TriageResult result = parseTriageResult(raw, needsVisionDecision);
        log.info("Triage 결과: {} (raw='{}', 연속PASS={})", result, raw, passCounter.get());

        if (result == TriageResult.PASS) {
            passCounter.increment();
        }
        return result;
    }

    /** ClaudeService와 동일 로직. SPEAK 라벨 변형에 best-effort 매칭. */
    private static TriageResult parseTriageResult(String normalized, boolean threeWay) {
        if (!threeWay) {
            return normalized.contains("SPEAK") ? TriageResult.SPEAK_WITH_VISION : TriageResult.PASS;
        }
        if (normalized.contains("SPEAKTEXT") || normalized.contains("SPEAK_TEXT")) {
            return TriageResult.SPEAK_TEXT_ONLY;
        }
        if (normalized.contains("SPEAKVISION") || normalized.contains("SPEAK_VISION")) {
            return TriageResult.SPEAK_WITH_VISION;
        }
        if (normalized.contains("SPEAK")) {
            return TriageResult.SPEAK_WITH_VISION;
        }
        return TriageResult.PASS;
    }

    @Override
    public String generateComment(String userText, String base64JpegImage) {
        String input = (userText == null || userText.isBlank())
                ? "(자동 트리거. 한 마디 해 봐)"
                : userText;

        history.addUser(input);

        String systemPrompt = character.getSystemPrompt();
        String pokemonCtx = pokemonContext.buildContext(input);
        if (pokemonCtx != null) {
            systemPrompt = systemPrompt + "\n\n" + pokemonCtx;
            log.debug("Pokemon 컨텍스트 주입됨");
        }
        systemPrompt = systemPrompt + passCounter.buildNudge("comment");

        List<Content> contents = buildContentsWithHistory(input, base64JpegImage);

        GenerateContentConfig config = GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(Part.fromText(systemPrompt)))
                .maxOutputTokens(properties.getGemini().getMaxTokens())
                .thinkingConfig(ThinkingConfig.builder().thinkingBudget(0).build())
                .build();

        log.debug("Comment 호출 (Gemini Flash). 컨텐츠 수: {}, 이미지: {}",
                contents.size(), base64JpegImage != null);
        GenerateContentResponse response = client.models.generateContent(
                properties.getGemini().getModel(), contents, config);

        String raw = safeText(response).trim();
        log.info("봇 raw 응답: {}", raw);

        String normalized = raw.replaceAll("[.\"'\\s]", "").toUpperCase();
        if (normalized.equals("PASS") || normalized.isEmpty()) {
            removeLastUserTurn();
            passCounter.increment();
            return PASS;
        }

        history.addAssistant(raw);
        passCounter.reset();
        return raw;
    }

    @Override
    public String generateIdleComment(String idleSystemPrompt, String triggerText, String base64JpegImage) {
        String input = (triggerText == null || triggerText.isBlank())
                ? "(능동 트리거)"
                : triggerText;

        // 캐릭터/히스토리/포켓몬/nudge 전부 미사용. idle 전용 시스템 프롬프트만.
        List<Content> contents = List.of(buildUserContent(input, base64JpegImage));

        GenerateContentConfig config = GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(Part.fromText(idleSystemPrompt)))
                .maxOutputTokens(properties.getGemini().getMaxTokens())
                .thinkingConfig(ThinkingConfig.builder().thinkingBudget(0).build())
                .build();

        log.debug("Idle comment 호출 (Gemini Flash, 이미지: {})", base64JpegImage != null);
        GenerateContentResponse response = client.models.generateContent(
                properties.getGemini().getModel(), contents, config);
        String raw = safeText(response).trim();
        log.info("Idle 봇 raw 응답: {}", raw);

        String normalized = raw.replaceAll("[.\"'\\s]", "").toUpperCase();
        if (normalized.equals("PASS") || normalized.isEmpty()) {
            return PASS;
        }
        return raw;
    }

    private String safeText(GenerateContentResponse response) {
        try {
            String t = response.text();
            return t == null ? "" : t;
        } catch (Exception e) {
            log.warn("Gemini 응답 텍스트 추출 실패: {}", e.getMessage());
            return "";
        }
    }

    private List<Content> buildContentsWithHistory(String currentUserInput, String base64JpegImage) {
        List<Content> result = new ArrayList<>();
        List<ConversationHistory.Turn> turns = history.snapshot();

        // 과거 턴은 텍스트만. Gemini의 role은 "user" / "model".
        for (int i = 0; i < turns.size() - 1; i++) {
            ConversationHistory.Turn turn = turns.get(i);
            String role = "user".equals(turn.role()) ? "user" : "model";
            result.add(Content.builder()
                    .role(role)
                    .parts(List.of(Part.fromText(turn.content())))
                    .build());
        }

        result.add(buildUserContent(currentUserInput, base64JpegImage));
        return result;
    }

    private Content buildUserContent(String text, String base64JpegImage) {
        List<Part> parts = new ArrayList<>();
        if (base64JpegImage != null) {
            byte[] bytes = Base64.getDecoder().decode(base64JpegImage);
            parts.add(Part.builder()
                    .inlineData(Blob.builder().data(bytes).mimeType("image/jpeg").build())
                    .build());
        }
        parts.add(Part.fromText(text));
        return Content.builder().role("user").parts(parts).build();
    }

    private void removeLastUserTurn() {
        List<ConversationHistory.Turn> snapshot = history.snapshot();
        if (snapshot.isEmpty()) return;
        ConversationHistory.Turn last = snapshot.get(snapshot.size() - 1);
        if ("user".equals(last.role())) {
            history.popLast();
        }
    }

    // ---------- 화면 번역 / 단발 vision/text 호출 ----------

    @Override
    public String analyzeImage(String systemPrompt, String userPrompt, String base64JpegImage) {
        return analyzeImage(systemPrompt, userPrompt, base64JpegImage, true);
    }

    @Override
    public String analyzeImage(String systemPrompt, String userPrompt,
                               String base64JpegImage, boolean useTriageModel) {
        return callRaw(pickModel(useTriageModel), systemPrompt, userPrompt, base64JpegImage, useTriageModel);
    }

    @Override
    public String analyzeText(String systemPrompt, String userPrompt, boolean useTriageModel) {
        return callRaw(pickModel(useTriageModel), systemPrompt, userPrompt, null, useTriageModel);
    }

    private String pickModel(boolean useTriageModel) {
        String triage = properties.getGemini().getTriageModel();
        String main = properties.getGemini().getModel();
        if (useTriageModel) return (triage == null || triage.isBlank()) ? main : triage;
        return main;
    }

    /** 페르소나/히스토리/nudge 미적용. systemPrompt+userPrompt(+image) 만으로 단발 호출. */
    private String callRaw(String model, String systemPrompt, String userPrompt,
                           String base64JpegImage, boolean useTriageModel) {
        List<Content> contents = List.of(buildUserContent(userPrompt, base64JpegImage));
        GenerateContentConfig config = GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(Part.fromText(systemPrompt)))
                .maxOutputTokens(properties.getGemini().getMaxTokens())
                .thinkingConfig(ThinkingConfig.builder().thinkingBudget(0).build())
                .build();
        log.debug("Gemini raw 호출 (model={}, image={}, triage={})", model, base64JpegImage != null, useTriageModel);
        GenerateContentResponse response = client.models.generateContent(model, contents, config);
        return safeText(response).trim();
    }
}
