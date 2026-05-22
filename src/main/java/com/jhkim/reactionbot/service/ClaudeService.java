package com.jhkim.reactionbot.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import com.jhkim.reactionbot.config.BotProperties;
import com.jhkim.reactionbot.config.CharacterConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Anthropic Claude 백엔드.
 *   - triage(): Haiku로 PASS/SPEAK 판단
 *   - generateComment(): Sonnet으로 코멘트 생성
 *
 * application.yml의 reaction-bot.llm.provider=anthropic (기본값)일 때만 빈 생성.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "reaction-bot.llm", name = "provider", havingValue = "anthropic", matchIfMissing = true)
public class ClaudeService implements LlmProvider {

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

    private final BotProperties properties;
    private final CharacterConfig character;
    private final ConversationHistory history;
    private final PokemonContextService pokemonContext;
    private final PassCounter passCounter;

    private AnthropicClient client;

    @PostConstruct
    public void init() {
        String apiKey = properties.getAnthropic().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            this.client = AnthropicOkHttpClient.fromEnv();
            log.info("Anthropic 클라이언트: 환경변수 사용");
        } else {
            this.client = AnthropicOkHttpClient.builder()
                    .apiKey(apiKey)
                    .build();
            log.info("Anthropic 클라이언트: application.yml 키 사용");
        }
        log.info("LLM provider=anthropic. 모델: comment={}, triage={}",
                properties.getAnthropic().getModel(),
                properties.getAnthropic().getTriageModel());
    }

    @Override
    public boolean triage(String userText) {
        String input = (userText == null || userText.isBlank())
                ? "(자동 트리거)"
                : userText;

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(input)
                .build());

        String systemPrompt = TRIAGE_SYSTEM + passCounter.buildNudge("triage");

        MessageCreateParams params = MessageCreateParams.builder()
                .model(properties.getAnthropic().getTriageModel())
                .maxTokens(10L)
                .system(systemPrompt)
                .messages(messages)
                .build();

        log.debug("Triage 호출 (Haiku, 텍스트 전용). 연속PASS: {}", passCounter.get());
        Message response = client.messages().create(params);
        String raw = extractText(response).toUpperCase().replaceAll("[^A-Z]", "");

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

        List<MessageParam> messages = buildMessagesWithHistory(input, base64JpegImage);

        MessageCreateParams params = MessageCreateParams.builder()
                .model(properties.getAnthropic().getModel())
                .maxTokens((long) properties.getAnthropic().getMaxTokens())
                .system(systemPrompt)
                .messages(messages)
                .build();

        log.debug("Comment 호출 (Sonnet). 메시지 수: {}, 이미지: {}",
                messages.size(), base64JpegImage != null);
        Message response = client.messages().create(params);

        String raw = extractText(response).trim();
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

    private String extractText(Message response) {
        return response.content().stream()
                .map(block -> block.text().map(t -> t.text()).orElse(""))
                .reduce("", String::concat);
    }

    private List<MessageParam> buildMessagesWithHistory(String currentUserInput, String base64JpegImage) {
        List<MessageParam> result = new ArrayList<>();
        List<ConversationHistory.Turn> turns = history.snapshot();

        for (int i = 0; i < turns.size() - 1; i++) {
            ConversationHistory.Turn turn = turns.get(i);
            result.add(MessageParam.builder()
                    .role("user".equals(turn.role()) ? MessageParam.Role.USER : MessageParam.Role.ASSISTANT)
                    .content(turn.content())
                    .build());
        }

        result.add(buildLastUserMessage(currentUserInput, base64JpegImage));
        return result;
    }

    private MessageParam buildLastUserMessage(String text, String base64JpegImage) {
        if (base64JpegImage != null) {
            List<ContentBlockParam> blocks = new ArrayList<>();
            blocks.add(ContentBlockParam.ofImage(ImageBlockParam.builder()
                    .source(Base64ImageSource.builder()
                            .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                            .data(base64JpegImage)
                            .build())
                    .build()));
            blocks.add(ContentBlockParam.ofText(TextBlockParam.builder()
                    .text(text)
                    .build()));
            return MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(blocks)
                    .build();
        }
        return MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(text)
                .build();
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
