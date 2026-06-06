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

    /**
     * multimodal-mode=ai-decide 일 때 사용. SPEAK를 두 갈래로 쪼개 vision 필요 여부까지 1차에서 결정.
     * 캡처/전송 비용·지연 절감. 텍스트 잡담·호명 응답은 화면 없이도 답할 수 있다는 가정.
     */
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
    public TriageResult triage(String userText, boolean needsVisionDecision) {
        String input = (userText == null || userText.isBlank())
                ? "(자동 트리거)"
                : userText;

        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(input)
                .build());

        String basePrompt = character.substitute(needsVisionDecision ? TRIAGE_SYSTEM_WITH_VISION : TRIAGE_SYSTEM);
        String systemPrompt = basePrompt + passCounter.buildNudge("triage");

        // ai-decide는 라벨이 더 길어서 토큰 여유. 5~12 정도면 충분.
        long maxTokens = needsVisionDecision ? 16L : 10L;

        MessageCreateParams params = MessageCreateParams.builder()
                .model(properties.getAnthropic().getTriageModel())
                .maxTokens(maxTokens)
                .system(systemPrompt)
                .messages(messages)
                .build();

        log.debug("Triage 호출 (Haiku, 텍스트 전용, vision-decide={}). 연속PASS: {}",
                needsVisionDecision, passCounter.get());
        Message response = client.messages().create(params);
        String raw = extractText(response).toUpperCase().replaceAll("[^A-Z_]", "");

        TriageResult result = parseTriageResult(raw, needsVisionDecision);
        log.info("Triage 결과: {} (raw='{}', 연속PASS={})", result, raw, passCounter.get());

        if (result == TriageResult.PASS) {
            passCounter.increment();
        }
        return result;
    }

    /**
     * - 2-way 모드(needsVisionDecision=false): "SPEAK" 포함하면 SPEAK_WITH_VISION (기존 동작 유지),
     *                                         아니면 PASS.
     * - 3-way 모드: "SPEAK_TEXT" 우선 매칭 → SPEAK_VISION → 둘 다 아니면 PASS.
     *   (모델이 라벨 변형을 뱉어도 토큰 유사도로 best-effort)
     */
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
        // "SPEAK"만 단독으로 왔으면 안전하게 vision으로 (캡처해도 손해 적음)
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

    @Override
    public String generateIdleComment(String idleSystemPrompt, String triggerText, String base64JpegImage) {
        String input = (triggerText == null || triggerText.isBlank())
                ? "(능동 트리거)"
                : triggerText;

        // idle은 캐릭터 프롬프트도, 히스토리도, pokemon 컨텍스트도, passCounter nudge도 사용 안 함.
        // 가벼운 혼잣말 톤이 목적. 시스템 프롬프트는 호출자가 준 idleSystemPrompt만 사용.
        List<MessageParam> messages = new ArrayList<>();
        messages.add(buildLastUserMessage(input, base64JpegImage));

        MessageCreateParams params = MessageCreateParams.builder()
                .model(properties.getAnthropic().getModel())
                .maxTokens((long) properties.getAnthropic().getMaxTokens())
                .system(idleSystemPrompt)
                .messages(messages)
                .build();

        log.debug("Idle comment 호출 (Sonnet, 이미지: {})", base64JpegImage != null);
        Message response = client.messages().create(params);
        String raw = extractText(response).trim();
        log.info("Idle 봇 raw 응답: {}", raw);

        String normalized = raw.replaceAll("[.\"'\\s]", "").toUpperCase();
        if (normalized.equals("PASS") || normalized.isEmpty()) {
            return PASS;
        }
        return raw;
    }

    /**
     * 페르소나·히스토리 거치지 않는 raw vision 호출 (포켓몬 오버레이 등 부가 기능용).
     * 히스토리에 영향 없음. triage·pokemon 컨텍스트·passCounter nudge 모두 미적용.
     * triageModel(저렴) 우선, 비어있으면 main model 사용.
     */
    @Override
    public String analyzeImage(String systemPrompt, String userPrompt, String base64JpegImage) {
        String model = properties.getAnthropic().getTriageModel();
        if (model == null || model.isBlank()) {
            model = properties.getAnthropic().getModel();
        }
        List<MessageParam> messages = new ArrayList<>();
        messages.add(buildLastUserMessage(userPrompt, base64JpegImage));

        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(1024L)
                .system(systemPrompt)
                .messages(messages)
                .build();
        log.debug("analyzeImage 호출 (model={}, image={})", model, base64JpegImage != null);
        Message response = client.messages().create(params);
        return extractText(response).trim();
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
