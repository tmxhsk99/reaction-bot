package com.jhkim.reactionbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jhkim.reactionbot.config.BotProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OBS WebSocket v5 클라이언트.
 * 송출 중인 씬의 스크린샷을 요청해서 Base64 JPEG로 받아옴.
 *
 * 프로토콜:
 *  - 서버 Hello(op 0) 수신 → 클라 Identify(op 1) 전송 → 서버 Identified(op 2) 수신
 *  - 인증 필요 시 sha256(sha256(password+salt) + challenge) 계산
 *  - Request(op 6) ↔ RequestResponse(op 7)는 requestId로 매칭
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ObsScreenshotClient {

    private final BotProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private final StringBuilder partialMessage = new StringBuilder();

    private volatile boolean identified = false;
    private volatile boolean enabled = false;

    @PostConstruct
    public void init() {
        // OBS 모드 아니면 연결 시도 자체를 안 함
        if (!"obs".equalsIgnoreCase(properties.getScreen().getSource())) {
            log.info("OBS 캡처 비활성화 (screen.source={})", properties.getScreen().getSource());
            return;
        }
        enabled = true;
        try {
            connect();
        } catch (Exception e) {
            log.warn("OBS WebSocket 초기 연결 실패. 캡처 시도 시 재연결함. ({})", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        WebSocket ws = socket.getAndSet(null);
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown").get(1, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
    }

    private synchronized void connect() throws Exception {
        BotProperties.Obs obs = properties.getScreen().getObs();
        URI uri = URI.create("ws://" + obs.getHost() + ":" + obs.getPort());
        log.info("OBS WebSocket 연결: {}", uri);

        CompletableFuture<Void> identifiedFuture = new CompletableFuture<>();
        identified = false;
        partialMessage.setLength(0);

        WebSocket ws = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(uri, new Listener(identifiedFuture))
                .get(obs.getTimeoutMs(), TimeUnit.MILLISECONDS);
        socket.set(ws);

        identifiedFuture.get(obs.getTimeoutMs(), TimeUnit.MILLISECONDS);
        identified = true;
        log.info("OBS WebSocket Identified 완료.");
    }

    /**
     * 현재 송출 중인 씬의 스크린샷을 Base64 JPEG로 반환.
     */
    public String captureBase64Jpeg() throws Exception {
        if (!enabled) {
            throw new IllegalStateException("OBS 캡처 비활성화 상태");
        }
        if (!identified) {
            connect();
        }

        String sourceName = properties.getScreen().getObs().getSourceName();
        if (sourceName == null || sourceName.isBlank()) {
            sourceName = getCurrentProgramScene();
        }

        ObjectNode reqData = objectMapper.createObjectNode()
                .put("sourceName", sourceName)
                .put("imageFormat", "jpg")
                .put("imageWidth", 672);
        JsonNode resp = request("GetSourceScreenshot", reqData);

        String dataUrl = resp.path("responseData").path("imageData").asText();
        // "data:image/jpg;base64,XXXX" 또는 "data:image/jpeg;base64,XXXX"
        int comma = dataUrl.indexOf(',');
        return comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
    }

    private String getCurrentProgramScene() throws Exception {
        JsonNode resp = request("GetCurrentProgramScene", objectMapper.createObjectNode());
        // OBS WebSocket v5에서 키 이름이 버전에 따라 다름 - 둘 다 시도
        JsonNode data = resp.path("responseData");
        String name = data.path("currentProgramSceneName").asText(null);
        if (name == null || name.isEmpty()) {
            name = data.path("sceneName").asText(null);
        }
        if (name == null || name.isEmpty()) {
            throw new IllegalStateException("현재 프로그램 씬 이름을 못 받음: " + resp);
        }
        return name;
    }

    private JsonNode request(String requestType, ObjectNode requestData) throws Exception {
        String requestId = UUID.randomUUID().toString();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("op", 6);
        ObjectNode d = payload.putObject("d");
        d.put("requestType", requestType);
        d.put("requestId", requestId);
        d.set("requestData", requestData);

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(requestId, future);
        try {
            sendText(payload.toString());
            JsonNode resp = future.get(properties.getScreen().getObs().getTimeoutMs(), TimeUnit.MILLISECONDS);
            JsonNode status = resp.path("requestStatus");
            if (!status.path("result").asBoolean(false)) {
                throw new IllegalStateException("OBS 요청 실패: " + status.toString());
            }
            return resp;
        } catch (TimeoutException e) {
            throw new IllegalStateException("OBS 응답 타임아웃: " + requestType, e);
        } finally {
            pending.remove(requestId);
        }
    }

    private void sendText(String text) {
        WebSocket ws = socket.get();
        if (ws == null) throw new IllegalStateException("OBS WebSocket 연결 안 됨");
        ws.sendText(text, true).join();
    }

    private void handleMessage(String text) {
        try {
            JsonNode msg = objectMapper.readTree(text);
            int op = msg.path("op").asInt();
            JsonNode d = msg.path("d");
            switch (op) {
                case 0 -> handleHello(d);   // Hello
                case 2 -> { /* Identified */ }
                case 7 -> handleResponse(d); // RequestResponse
                default -> log.trace("OBS 메시지 무시 (op={})", op);
            }
        } catch (Exception e) {
            log.warn("OBS 메시지 처리 실패: {}", e.getMessage());
        }
    }

    private void handleHello(JsonNode d) throws Exception {
        ObjectNode identify = objectMapper.createObjectNode();
        identify.put("op", 1);
        ObjectNode payload = identify.putObject("d");
        payload.put("rpcVersion", 1);
        payload.put("eventSubscriptions", 0);

        JsonNode auth = d.path("authentication");
        if (!auth.isMissingNode() && !auth.isNull()) {
            String password = properties.getScreen().getObs().getPassword();
            if (password == null || password.isEmpty()) {
                throw new IllegalStateException(
                        "OBS WebSocket이 비밀번호를 요구하는데 OBS_PASSWORD가 설정되지 않음");
            }
            String challenge = auth.path("challenge").asText();
            String salt = auth.path("salt").asText();
            payload.put("authentication", computeAuth(password, salt, challenge));
        }
        sendText(identify.toString());
    }

    private void handleResponse(JsonNode d) {
        String requestId = d.path("requestId").asText();
        CompletableFuture<JsonNode> future = pending.get(requestId);
        if (future != null) {
            future.complete(d);
        }
    }

    private static String computeAuth(String password, String salt, String challenge) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        String secret = Base64.getEncoder().encodeToString(
                sha.digest((password + salt).getBytes(StandardCharsets.UTF_8)));
        sha.reset();
        return Base64.getEncoder().encodeToString(
                sha.digest((secret + challenge).getBytes(StandardCharsets.UTF_8)));
    }

    /** WebSocket 이벤트 리스너. partial 프레임 합치고 텍스트 메시지 처리. */
    private class Listener implements WebSocket.Listener {
        private final CompletableFuture<Void> identifiedFuture;

        Listener(CompletableFuture<Void> identifiedFuture) {
            this.identifiedFuture = identifiedFuture;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partialMessage.append(data);
            if (last) {
                String complete = partialMessage.toString();
                partialMessage.setLength(0);
                handleMessage(complete);
                // Identified(op 2) 받았으면 future 완료
                try {
                    JsonNode msg = objectMapper.readTree(complete);
                    if (msg.path("op").asInt() == 2 && !identifiedFuture.isDone()) {
                        identifiedFuture.complete(null);
                    }
                } catch (Exception ignored) {
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("OBS WebSocket 닫힘 (code={}, reason={})", statusCode, reason);
            identified = false;
            socket.compareAndSet(webSocket, null);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("OBS WebSocket 오류: {}", error.getMessage());
            identified = false;
            if (!identifiedFuture.isDone()) {
                identifiedFuture.completeExceptionally(error);
            }
        }
    }
}
