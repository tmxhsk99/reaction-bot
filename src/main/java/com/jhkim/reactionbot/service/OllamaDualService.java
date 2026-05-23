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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 로컬 Ollama 2-스테이지 파이프라인.
 *  1) Vision 모델 (qwen2.5vl 등): 화면 캡처 → 한국어 1~2문장 묘사
 *  2) Text 모델  (qwen2.5 등)  : 묘사 + 유저 발화 + 캐릭터 프롬프트 → 반응 생성
 *
 * 단일 VL provider({@link OllamaService}) 대비:
 *  - VL 호출엔 캐릭터 프롬프트가 안 들어가 짧고 빠름
 *  - 텍스트 호출은 이미지 토큰 0 → 디코드 속도↑
 *  - thinking 강제 VL 모델(qwen3-vl) 회피 가능
 *
 * 두 모델 모두 keep_alive로 메모리 상주 → 콜드 로드 비용은 첫 호출(또는 warm-up) 1회.
 * reaction-bot.llm.provider=ollama-dual 일 때만 빈 생성.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "reaction-bot.llm", name = "provider", havingValue = "ollama-dual")
public class OllamaDualService implements LlmProvider {

    private static final Pattern THINK_BLOCK =
            Pattern.compile("<think>[\\s\\S]*?</think>", Pattern.MULTILINE);

    /** 텍스트 단계 적극성 부스터. character.yml 룰을 덮어쓰지 않고 보강. */
    private static final String ASSERTIVE_NUDGE = """


            [로컬 모드 추가 지침]
            - 호출 비용 무관 환경. 평소보다 한 발 더 적극적으로 끼어들어라.
            - 위 "PASS 3가지 조건"이 명확히 충족될 때만 PASS. 애매하면 무조건 한 마디.
            - 같은 톤·같은 패턴 반복은 여전히 금지. 매번 다른 각도(놀림/응원/예측/딴지/자랑)로 변주.
            """;

    /**
     * 비전 단계 시스템 프롬프트. 묘사만 시키고 캐릭터·감상은 텍스트 단계로 미룸.
     * 짧게 받아야 텍스트 단계 컨텍스트가 안 부풀어남.
     */
    private static final String VISION_SYSTEM_PROMPT = """
            너는 화면 분석기다. 주어진 스크린샷을 한국어 1~2문장으로 간결히 묘사해라.
            포함할 것:
              - 무엇이 보이는지 (게임/방송 화면/UI 등)
              - 진행 상황 (전투/메뉴/이동/결과 화면 등)
              - 눈에 띄는 텍스트·숫자·캐릭터 이름
            지키기:
              - 사실만. 의견·감상·농담·인사말 금지.
              - "스크린샷에는…" 같은 군더더기 없이 바로 묘사.
              - 모르는 것은 추측하지 말고 빼라.
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
        BotProperties.OllamaDual cfg = properties.getOllamaDual();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        log.info("LLM provider=ollama-dual. baseUrl={}, vision={}, text={}, keep_alive(v/t)={}/{}",
                cfg.getBaseUrl(),
                cfg.getVisionModel().getModel(),
                cfg.getTextModel().getModel(),
                cfg.getVisionModel().getKeepAlive(),
                cfg.getTextModel().getKeepAlive());
        if (cfg.isWarmupOnStart()) {
            warmupAsync();
        }
    }

    /** 두 모델 모두 더미 호출로 사전 로드. 실패해도 무시. */
    private void warmupAsync() {
        Thread.startVirtualThread(() -> {
            BotProperties.OllamaDual cfg = properties.getOllamaDual();
            warmupOne(cfg.getVisionModel().getModel(), cfg.getVisionModel().getKeepAlive(), "vision");
            warmupOne(cfg.getTextModel().getModel(), cfg.getTextModel().getKeepAlive(), "text");
        });
    }

    private void warmupOne(String model, String keepAlive, String label) {
        try {
            long start = System.currentTimeMillis();
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("stream", false);
            body.put("keep_alive", keepAlive);
            ObjectNode opts = mapper.createObjectNode();
            opts.put("num_predict", 1);
            body.set("options", opts);
            ArrayNode msgs = mapper.createArrayNode();
            ObjectNode m = mapper.createObjectNode();
            m.put("role", "user");
            m.put("content", "hi");
            msgs.add(m);
            body.set("messages", msgs);

            HttpRequest req = newRequest(body);
            log.info("Ollama warm-up 시작 ({} 모델 사전 로드: {})...", label, model);
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - start;
            if (resp.statusCode() / 100 == 2) {
                log.info("Ollama warm-up 완료 ({} {}초).", label, elapsed / 1000);
            } else {
                log.warn("Ollama warm-up 응답 비정상 ({} HTTP {}, {}초): {}",
                        label, resp.statusCode(), elapsed / 1000, resp.body());
            }
        } catch (Exception e) {
            log.warn("Ollama warm-up 실패 ({}): {}", label, e.getMessage());
        }
    }

    /** dual은 두 단계가 한 호출 안에서 묶여 있으므로 별도 triage 단계 없음. */
    @Override
    public boolean hasSeparateTriage() {
        return false;
    }

    /** 비전 단계에서 이미지 처리. orchestrator는 캡처를 항상 넘기게 됨. */
    @Override
    public boolean acceptsImage() {
        return true;
    }

    @Override
    public boolean triage(String userText) {
        return true; // 호출되지 않음 (hasSeparateTriage=false)
    }

    @Override
    public String generateComment(String userText, String base64JpegImage) {
        String input = (userText == null || userText.isBlank())
                ? "(자동 트리거. 한 마디 해 봐)"
                : userText;

        // 1단계: 화면 묘사 (VL stream + 첫 문장 도달 시 조기 종료)
        String sceneDescription = "";
        if (base64JpegImage != null && !base64JpegImage.isBlank()) {
            try {
                sceneDescription = describeScene(base64JpegImage);
                log.info("VL 화면 묘사: {}", sceneDescription);
            } catch (Exception e) {
                log.warn("VL 호출 실패. 화면 묘사 없이 진행: {}", e.getMessage());
            }
        } else {
            log.debug("이미지 없이 진행 (text-only 트리거).");
        }

        // 합성된 user content를 히스토리에 저장 → 다음 턴에서도 직전 화면 컨텍스트가 자동으로 따라옴.
        // (PASS면 아래 removeLastUserTurn으로 다시 빼냄.)
        String composedContent = composeUserContent(input, sceneDescription);
        history.addUser(composedContent);

        // 2단계: 캐릭터 반응
        String raw;
        try {
            raw = generateReaction(input);
        } catch (Exception e) {
            log.error("텍스트 모델 호출 실패", e);
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

    // ---------- 1단계: Vision (stream + 조기 종료) ----------

    /** 너무 짧은 묘사는 무의미하므로 이 길이 이상부터 종결 부호 도달 시 조기 종료. */
    private static final int VISION_MIN_CHARS = 15;

    /**
     * 화면을 stream으로 받다가 첫 문장이 완성되는 즉시 connection close → Ollama 생성 중단.
     * VL이 200토큰 묘사할 시간을 1~2문장 한 줄에서 끊어 체감 응답 시간을 크게 단축.
     */
    private String describeScene(String base64JpegImage) throws Exception {
        BotProperties.VisionModel vm = properties.getOllamaDual().getVisionModel();

        ObjectNode body = mapper.createObjectNode();
        body.put("model", vm.getModel());
        body.put("stream", true);                   // 핵심: stream으로 받아야 중간 종료 가능
        body.put("keep_alive", vm.getKeepAlive());

        ObjectNode options = mapper.createObjectNode();
        options.put("num_predict", vm.getMaxTokens());  // 안전 cutoff. 보통 조기 종료가 먼저 걸림
        if (vm.getNumCtx() != null) options.put("num_ctx", vm.getNumCtx());
        if (vm.getTemperature() != null) options.put("temperature", vm.getTemperature());
        body.set("options", options);

        // 비전 단계 시스템 프롬프트 + yml의 추가 룰 (한국어 강제·고유명사 정확 인용 등).
        String visionSystem = VISION_SYSTEM_PROMPT;
        String visionExtra = vm.getExtraSystemPrompt();
        if (visionExtra != null && !visionExtra.isBlank()) {
            visionSystem = visionSystem + "\n\n" + visionExtra;
        }

        ArrayNode messages = mapper.createArrayNode();
        ObjectNode sys = mapper.createObjectNode();
        sys.put("role", "system");
        sys.put("content", visionSystem);
        messages.add(sys);

        ObjectNode user = mapper.createObjectNode();
        user.put("role", "user");
        user.put("content", "이 화면을 묘사해라.");
        ArrayNode images = mapper.createArrayNode();
        images.add(base64JpegImage);
        user.set("images", images);
        messages.add(user);

        body.set("messages", messages);

        HttpRequest req = newRequest(body);
        log.debug("VL 호출(stream). model={}", vm.getModel());
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new RuntimeException("VL HTTP " + resp.statusCode() + ": " + err);
        }

        StringBuilder content = new StringBuilder();
        boolean earlyStop = false;
        long start = System.currentTimeMillis();
        // try-with-resources: 루프 break 시 InputStream close → HTTP/1.1 chunked 응답 도중
        // 클라이언트가 끊으면 Ollama가 broken pipe로 모델 생성을 중단함.
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                JsonNode chunk = mapper.readTree(line);
                String piece = chunk.path("message").path("content").asText("");
                if (!piece.isEmpty()) content.append(piece);
                if (chunk.path("done").asBoolean(false)) break;
                if (isVisionSentenceComplete(content)) {
                    earlyStop = true;
                    break;
                }
            }
        }
        long elapsed = System.currentTimeMillis() - start;
        if (earlyStop) {
            log.debug("VL 묘사 조기 종료 ({}자, {}ms)", content.length(), elapsed);
        } else {
            log.debug("VL 묘사 정상 종료 ({}자, {}ms)", content.length(), elapsed);
        }
        return stripThinkBlocks(content.toString()).trim();
    }

    /**
     * 최소 길이 이상 + 마지막 문자가 문장 종결자면 첫 문장이 완성된 것으로 간주.
     * 한국어/영어/일본어 종결 부호 + 줄바꿈을 모두 인식.
     */
    private static boolean isVisionSentenceComplete(StringBuilder s) {
        int len = s.length();
        if (len < VISION_MIN_CHARS) return false;
        char last = s.charAt(len - 1);
        return last == '.' || last == '。'
                || last == '!' || last == '?'
                || last == '!' || last == '?'
                || last == '\n';
    }

    // ---------- 2단계: Text ----------

    private String generateReaction(String userText) throws Exception {
        BotProperties.OllamaDual cfg = properties.getOllamaDual();
        BotProperties.TextModel tm = cfg.getTextModel();

        String systemPrompt = character.getSystemPrompt();
        String pokemonCtx = pokemonContext.buildContext(userText);
        if (pokemonCtx != null) {
            systemPrompt = systemPrompt + "\n\n" + pokemonCtx;
            log.debug("Pokemon 컨텍스트 주입됨");
        }
        if (cfg.isAssertive()) {
            systemPrompt = systemPrompt + ASSERTIVE_NUDGE;
        }
        // 텍스트(발화) 단계 전용 출력 규칙 — 한국어 강제·이모지 금지·중복 변주 등.
        String textExtra = tm.getExtraSystemPrompt();
        if (textExtra != null && !textExtra.isBlank()) {
            systemPrompt = systemPrompt + "\n\n" + textExtra;
        }
        systemPrompt = systemPrompt + passCounter.buildNudge("comment");
        if (!tm.isThink()) {
            systemPrompt = systemPrompt + "\n\n/no_think";
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("model", tm.getModel());
        body.put("stream", false);
        body.put("think", tm.isThink());
        body.put("keep_alive", tm.getKeepAlive());

        ObjectNode options = mapper.createObjectNode();
        options.put("num_predict", tm.getMaxTokens());
        if (tm.getNumCtx() != null) options.put("num_ctx", tm.getNumCtx());
        if (tm.getTemperature() != null) options.put("temperature", tm.getTemperature());
        if (tm.getTopP() != null) options.put("top_p", tm.getTopP());
        body.set("options", options);

        ArrayNode messages = mapper.createArrayNode();
        ObjectNode sys = mapper.createObjectNode();
        sys.put("role", "system");
        sys.put("content", systemPrompt);
        messages.add(sys);

        // history snapshot은 이미 현재 turn(합성된 user content)까지 포함하고 있다.
        // 과거 turn들도 각각 그 시점의 화면 묘사가 user content에 합성된 채로 저장되어 있어,
        // 다음 턴에서 "직전 화면은 X였는데 지금은 Y" 같은 흐름 비교가 자연스럽게 가능.
        List<ConversationHistory.Turn> turns = history.snapshot();
        for (ConversationHistory.Turn turn : turns) {
            ObjectNode msg = mapper.createObjectNode();
            msg.put("role", turn.role());
            msg.put("content", turn.content());
            messages.add(msg);
        }

        body.set("messages", messages);

        HttpRequest req = newRequest(body);
        log.debug("텍스트 모델 호출. model={}, msgs={}", tm.getModel(), messages.size());
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Text HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = mapper.readTree(resp.body());
        JsonNode message = root.path("message");
        String content = message.path("content").asText("");
        if (content.isBlank()) {
            String thinking = message.path("thinking").asText("");
            if (!thinking.isBlank()) {
                log.warn("Text 응답 content는 비어있고 thinking에만 내용. thinking 일부: {}",
                        thinking.length() > 200 ? thinking.substring(0, 200) + "…" : thinking);
            }
        }
        return content;
    }

    private String composeUserContent(String userText, String sceneDescription) {
        if (sceneDescription == null || sceneDescription.isBlank()) {
            return userText;
        }
        return "[현재 화면] " + sceneDescription + "\n[방금 들은 말] " + userText;
    }

    // ---------- 공용 ----------

    private HttpRequest newRequest(ObjectNode body) throws Exception {
        BotProperties.OllamaDual cfg = properties.getOllamaDual();
        String endpoint = stripTrailingSlash(cfg.getBaseUrl()) + "/api/chat";
        return HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(cfg.getRequestTimeoutSec()))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
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
