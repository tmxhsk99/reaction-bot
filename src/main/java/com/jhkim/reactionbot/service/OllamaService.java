package com.jhkim.reactionbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jhkim.reactionbot.config.BotProperties;
import com.jhkim.reactionbot.config.CharacterConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 로컬 Ollama 백엔드 (qwen3, qwen3-vl 등 self-host LLM).
 *
 * 상용 API와 달리 호출 비용이 들지 않으므로 1회 호출 모드로 운영:
 *  - {@link #hasSeparateTriage()} = false  → orchestrator가 triage 단계 생략, 1회 호출로 끝
 *  - {@link #acceptsImage()}              → reaction-bot.ollama.vision 설정값
 *    - true  : 화면 캡처 + payload.message.images에 base64 첨부 (qwen3-vl, qwen2.5vl 등 VL 모델)
 *    - false : 캡처도 생략 (qwen3 등 text-only 모델. 더 빠름)
 *  - PASS/SPEAK 판단은 character.yml 시스템 프롬프트의 PASS 룰로 generateComment 내에서 처리.
 *
 * reaction-bot.llm.provider=ollama 일 때만 빈 생성.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "reaction-bot.llm", name = "provider", havingValue = "ollama")
public class OllamaService implements LlmProvider {

    private static final Pattern THINK_BLOCK =
            Pattern.compile("<think>[\\s\\S]*?</think>", Pattern.MULTILINE);

    /**
     * 로컬 모드 전용 적극성 부스터. 상용 API와 달리 호출 비용 무관이라
     * PASS를 더 줄이고 적극적으로 한 마디 던지게 유도. character.yml 룰을 덮어쓰지 않고 보강.
     */
    private static final String ASSERTIVE_NUDGE = """


            [로컬 모드 추가 지침]
            - 호출 비용 무관 환경. 평소보다 한 발 더 적극적으로 끼어들어라.
            - 위 "PASS 3가지 조건"이 명확히 충족될 때만 PASS. 애매하면 무조건 한 마디.
            - 같은 톤·같은 패턴 반복은 여전히 금지. 매번 다른 각도(놀림/응원/예측/딴지/자랑)로 변주.
            """;

    private final BotProperties properties;
    private final CharacterConfig character;
    private final ConversationHistory history;
    private final PokemonContextService pokemonContext;
    private final PassCounter passCounter;

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpClient http;

    @PostConstruct
    public void init() {
        BotProperties.Ollama ollama = properties.getOllama();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        log.info("LLM provider=ollama. baseUrl={}, model={}, single-call mode (triage off, vision={}, keep_alive={})",
                ollama.getBaseUrl(), ollama.getModel(),
                ollama.isVision() ? "on" : "off", ollama.getKeepAlive());
        if (ollama.isWarmupOnStart()) {
            warmupAsync();
        }
    }

    /**
     * 백그라운드로 더미 호출 1회 보내서 모델을 메모리에 미리 로드.
     * 8B 모델 콜드 로드는 30~60초 걸리므로 첫 발화를 기다리게 두지 않기 위함.
     * 실패해도 무시 (실제 발화 시 다시 시도).
     */
    private void warmupAsync() {
        Thread.startVirtualThread(() -> {
            BotProperties.Ollama ollama = properties.getOllama();
            try {
                long start = System.currentTimeMillis();
                ObjectNode body = mapper.createObjectNode();
                body.put("model", ollama.getModel());
                body.put("stream", false);
                body.put("keep_alive", ollama.getKeepAlive());
                // num_predict=1로 최소만 생성. 모델 로딩이 목적.
                ObjectNode opts = mapper.createObjectNode();
                opts.put("num_predict", 1);
                body.set("options", opts);
                ArrayNode msgs = mapper.createArrayNode();
                ObjectNode m = mapper.createObjectNode();
                m.put("role", "user");
                m.put("content", "hi");
                msgs.add(m);
                body.set("messages", msgs);

                String endpoint = stripTrailingSlash(ollama.getBaseUrl()) + "/api/chat";
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(ollama.getRequestTimeoutSec()))
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .build();
                log.info("Ollama warm-up 시작 (모델 사전 로드)...");
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                long elapsed = System.currentTimeMillis() - start;
                if (resp.statusCode() / 100 == 2) {
                    log.info("Ollama warm-up 완료 ({}초). 첫 발화 즉시 응답 가능.", elapsed / 1000);
                } else {
                    log.warn("Ollama warm-up 응답 비정상 (HTTP {}, {}초): {}",
                            resp.statusCode(), elapsed / 1000, resp.body());
                }
            } catch (Exception e) {
                log.warn("Ollama warm-up 실패 (실제 발화 때 재시도): {}", e.getMessage());
            }
        });
    }

    @Override
    public boolean hasSeparateTriage() {
        return false;
    }

    @Override
    public boolean acceptsImage() {
        return properties.getOllama().isVision();
    }

    /** 단일 호출 모드라 실제로 호출되지 않지만 인터페이스 계약상 항상 SPEAK. */
    @Override
    public boolean triage(String userText) {
        return true;
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
        if (properties.getOllama().isAssertive()) {
            systemPrompt = systemPrompt + ASSERTIVE_NUDGE;
        }
        systemPrompt = systemPrompt + passCounter.buildNudge("comment");
        // qwen3 계열은 top-level "think: false" 를 무시하고 message.thinking 필드에 응답을 채워 넣는 경우가 있다.
        // 그러면 message.content 가 빈 채로 와서 PASS 로 떨어진다. 시스템 프롬프트 끝에 /no_think 토큰을 박아
        // 추론 모드 자체를 끄는 게 가장 확실한 차단.
        if (!properties.getOllama().isThink()) {
            systemPrompt = systemPrompt + "\n\n/no_think";
        }

        String raw;
        try {
            raw = callOllama(systemPrompt, input, base64JpegImage);
        } catch (Exception e) {
            log.error("Ollama 호출 실패", e);
            removeLastUserTurn();
            return PASS;
        }

        String cleaned = stripThinkBlocks(raw).trim();
        log.info("봇 raw 응답: {}", cleaned);

        String normalized = cleaned.replaceAll("[.\"'\\s]", "").toUpperCase();
        if (normalized.equals("PASS") || normalized.isEmpty()) {
            removeLastUserTurn();
            passCounter.increment();
            return PASS;
        }

        history.addAssistant(cleaned);
        passCounter.reset();
        return cleaned;
    }

    private String callOllama(String systemPrompt, String currentUserInput, String base64JpegImage) throws Exception {
        BotProperties.Ollama ollama = properties.getOllama();

        ObjectNode body = mapper.createObjectNode();
        body.put("model", ollama.getModel());
        body.put("stream", false);
        // qwen3 thinking 모드 토글. Ollama 0.9+는 top-level "think" 지원. 구버전이면 무시됨.
        body.put("think", ollama.isThink());
        // 모델 메모리 유지 시간 ("10m", "1h", "-1" 등). 매 호출마다 갱신되어 콜드 로드 방지.
        body.put("keep_alive", ollama.getKeepAlive());

        ObjectNode options = mapper.createObjectNode();
        options.put("num_predict", ollama.getMaxTokens());
        // 컨텍스트 윈도우 명시. VL 모델은 KV-cache가 VRAM을 크게 잡아먹어 OOM 유발 가능.
        // 빈 값이면 모델 디폴트(보통 4096) 사용.
        if (ollama.getNumCtx() != null) {
            options.put("num_ctx", ollama.getNumCtx());
        }
        if (ollama.getTemperature() != null) {
            options.put("temperature", ollama.getTemperature());
        }
        if (ollama.getTopP() != null) {
            options.put("top_p", ollama.getTopP());
        }
        body.set("options", options);

        ArrayNode messages = mapper.createArrayNode();
        ObjectNode sys = mapper.createObjectNode();
        sys.put("role", "system");
        sys.put("content", systemPrompt);
        messages.add(sys);

        // 마지막 turn은 방금 add한 현재 입력이므로 size-1까지가 과거 히스토리.
        // 과거 턴엔 이미지를 다시 보내지 않음 (토큰·VRAM 절약, 최신 화면이 중요).
        List<ConversationHistory.Turn> turns = history.snapshot();
        for (int i = 0; i < turns.size() - 1; i++) {
            ConversationHistory.Turn turn = turns.get(i);
            ObjectNode msg = mapper.createObjectNode();
            msg.put("role", turn.role());
            msg.put("content", turn.content());
            messages.add(msg);
        }
        ObjectNode current = mapper.createObjectNode();
        current.put("role", "user");
        current.put("content", currentUserInput);
        // VL 모델용 이미지 첨부. Ollama /api/chat 컨벤션: message.images = [base64, ...]
        if (base64JpegImage != null && !base64JpegImage.isBlank()) {
            ArrayNode images = mapper.createArrayNode();
            images.add(base64JpegImage);
            current.set("images", images);
        }
        messages.add(current);

        body.set("messages", messages);

        String endpoint = stripTrailingSlash(ollama.getBaseUrl()) + "/api/chat";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(ollama.getRequestTimeoutSec()))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        log.debug("Ollama 호출. 메시지 수: {}, model: {}, 이미지: {}",
                messages.size(), ollama.getModel(), base64JpegImage != null);
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Ollama HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = mapper.readTree(resp.body());
        JsonNode message = root.path("message");
        String content = message.path("content").asText("");
        // qwen3 계열이 thinking 모드로 응답하면 content는 빈 채로 오고 thinking 필드에만 내용이 채워진다.
        // /no_think 토큰으로 막아도 모델이 무시하는 경우가 있어 진단용으로 로깅만 남김.
        if (content.isBlank()) {
            String thinking = message.path("thinking").asText("");
            if (!thinking.isBlank()) {
                log.warn("Ollama 응답 content는 비어있고 thinking에만 내용이 있음 (thinking 모드 강제됨). " +
                        "thinking 일부: {}", thinking.length() > 200 ? thinking.substring(0, 200) + "…" : thinking);
            }
        }
        return content;
    }

    private static String stripTrailingSlash(String s) {
        if (s == null || s.isEmpty()) return s;
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') end--;
        return s.substring(0, end);
    }

    private String stripThinkBlocks(String s) {
        if (s == null) return "";
        return THINK_BLOCK.matcher(s).replaceAll("");
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
