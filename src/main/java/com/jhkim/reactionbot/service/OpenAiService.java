package com.jhkim.reactionbot.service;

import com.jhkim.reactionbot.config.BotProperties;
import com.jhkim.reactionbot.config.CharacterConfig;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * OpenAI API 백엔드. ClaudeService와 동일한 2단계 흐름.
 *   - triage(): 저렴한 모델로 PASS/SPEAK 판단 (텍스트만)
 *   - generateComment(): vision 가능 모델로 코멘트 생성 (이미지는 base64 data URL)
 *
 * application.yml의 reaction-bot.llm.provider=openai 일 때만 빈 생성.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "reaction-bot.llm", name = "provider", havingValue = "openai")
public class OpenAiService implements LlmProvider {

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

    private OpenAIClient client;

    @PostConstruct
    public void init() {
        String apiKey = properties.getOpenai().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            this.client = OpenAIOkHttpClient.fromEnv();
            log.info("OpenAI 클라이언트: 환경변수(OPENAI_API_KEY) 사용");
        } else {
            this.client = OpenAIOkHttpClient.builder()
                    .apiKey(apiKey)
                    .build();
            log.info("OpenAI 클라이언트: application.yml 키 사용");
        }
        log.info("LLM provider=openai. 모델: comment={}, triage={}",
                properties.getOpenai().getModel(),
                properties.getOpenai().getTriageModel());
    }

    @Override
    public TriageResult triage(String userText, boolean needsVisionDecision) {
        String input = (userText == null || userText.isBlank()) ? "(자동 트리거)" : userText;

        String basePrompt = character.substitute(needsVisionDecision ? TRIAGE_SYSTEM_WITH_VISION : TRIAGE_SYSTEM);
        String systemPrompt = basePrompt + passCounter.buildNudge("triage");

        long maxTokens = needsVisionDecision ? 16L : 10L;

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(properties.getOpenai().getTriageModel())
                .maxCompletionTokens(maxTokens)
                .addSystemMessage(systemPrompt)
                .addUserMessage(input)
                .build();

        log.debug("Triage 호출 (OpenAI, 텍스트 전용, vision-decide={}). 연속PASS: {}",
                needsVisionDecision, passCounter.get());
        ChatCompletion response = client.chat().completions().create(params);
        String raw = extractText(response).toUpperCase().replaceAll("[^A-Z_]", "");

        TriageResult result = parseTriageResult(raw, needsVisionDecision);
        log.info("Triage 결과: {} (raw='{}', 연속PASS={})", result, raw, passCounter.get());

        if (result == TriageResult.PASS) {
            passCounter.increment();
        }
        return result;
    }

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

        ChatCompletionCreateParams params = buildParamsWithHistory(systemPrompt, input, base64JpegImage);

        log.debug("Comment 호출 (OpenAI). 이미지: {}", base64JpegImage != null);
        ChatCompletion response = client.chat().completions().create(params);

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

        // idle은 캐릭터 프롬프트·히스토리·pokemon·nudge 미사용. 호출자가 준 시스템 프롬프트만.
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(properties.getOpenai().getModel())
                .maxCompletionTokens((long) properties.getOpenai().getMaxTokens())
                .addSystemMessage(idleSystemPrompt);
        addLastUserMessage(builder, input, base64JpegImage);

        log.debug("Idle comment 호출 (OpenAI, 이미지: {})", base64JpegImage != null);
        ChatCompletion response = client.chat().completions().create(builder.build());
        String raw = extractText(response).trim();
        log.info("Idle 봇 raw 응답: {}", raw);

        String normalized = raw.replaceAll("[.\"'\\s]", "").toUpperCase();
        if (normalized.equals("PASS") || normalized.isEmpty()) {
            return PASS;
        }
        return raw;
    }

    private ChatCompletionCreateParams buildParamsWithHistory(String systemPrompt,
                                                              String currentUserInput,
                                                              String base64JpegImage) {
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(properties.getOpenai().getModel())
                .maxCompletionTokens((long) properties.getOpenai().getMaxTokens())
                .addSystemMessage(systemPrompt);

        List<ConversationHistory.Turn> turns = history.snapshot();
        for (int i = 0; i < turns.size() - 1; i++) {
            ConversationHistory.Turn turn = turns.get(i);
            if ("user".equals(turn.role())) {
                builder.addUserMessage(turn.content());
            } else {
                builder.addAssistantMessage(turn.content());
            }
        }

        addLastUserMessage(builder, currentUserInput, base64JpegImage);
        return builder.build();
    }

    /** 마지막 user 메시지. 이미지가 있으면 text+image content part 배열로, 없으면 단순 텍스트로. */
    private void addLastUserMessage(ChatCompletionCreateParams.Builder builder,
                                    String text, String base64JpegImage) {
        if (base64JpegImage != null && !base64JpegImage.isBlank()) {
            ChatCompletionContentPart textPart = ChatCompletionContentPart.ofText(
                    ChatCompletionContentPartText.builder().text(text).build());
            ChatCompletionContentPart imagePart = ChatCompletionContentPart.ofImageUrl(
                    ChatCompletionContentPartImage.builder()
                            .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder()
                                    .url("data:image/jpeg;base64," + base64JpegImage)
                                    .build())
                            .build());
            builder.addUserMessageOfArrayOfContentParts(List.of(textPart, imagePart));
        } else {
            builder.addUserMessage(text);
        }
    }

    private String extractText(ChatCompletion response) {
        return response.choices().stream()
                .findFirst()
                .flatMap(choice -> choice.message().content())
                .orElse("");
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
        String triage = properties.getOpenai().getTriageModel();
        String main = properties.getOpenai().getModel();
        if (useTriageModel) return (triage == null || triage.isBlank()) ? main : triage;
        return main;
    }

    /** 페르소나/히스토리 미적용. 단발 호출. */
    private String callRaw(String model, String systemPrompt, String userPrompt,
                           String base64JpegImage, boolean useTriageModel) {
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(model)
                .maxCompletionTokens((long) properties.getOpenai().getMaxTokens())
                .addSystemMessage(systemPrompt);
        addLastUserMessage(builder, userPrompt, base64JpegImage);
        log.debug("OpenAI raw 호출 (model={}, image={}, triage={})", model, base64JpegImage != null, useTriageModel);
        ChatCompletion response = client.chat().completions().create(builder.build());
        return extractText(response).trim();
    }
}
