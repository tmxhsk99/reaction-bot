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
            방금 스트리머가 한 말과 화면을 보고, 봇이 "지금 한 마디 해도 될 상황인가" 판단하세요.
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
    public boolean triage(String userText, String base64JpegImage) {
        String input = (userText == null || userText.isBlank())
                ? "(자동 트리거)"
                : userText;

        String systemPrompt = TRIAGE_SYSTEM + passCounter.buildNudge("triage");

        List<Content> contents = new ArrayList<>();
        contents.add(buildUserContent(input, base64JpegImage));

        GenerateContentConfig config = GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(Part.fromText(systemPrompt)))
                .maxOutputTokens(10)
                .thinkingConfig(ThinkingConfig.builder().thinkingBudget(0).build())
                .build();

        log.debug("Triage 호출 (Gemini Flash-Lite). 이미지: {}, 연속PASS: {}",
                base64JpegImage != null, passCounter.get());
        GenerateContentResponse response = client.models.generateContent(
                properties.getGemini().getTriageModel(), contents, config);
        String raw = safeText(response).toUpperCase().replaceAll("[^A-Z]", "");

        boolean speak = raw.contains("SPEAK");
        log.info("Triage 결과: {} (raw='{}', 연속PASS={})",
                speak ? "SPEAK" : "PASS", raw, passCounter.get());

        if (!speak) {
            passCounter.increment();
        }
        return speak;
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
}
