package com.jhkim.reactionbot.controller;

import com.jhkim.reactionbot.config.BotProperties;
import com.jhkim.reactionbot.dto.SpeechDto;
import com.jhkim.reactionbot.service.ConversationHistory;
import com.jhkim.reactionbot.service.PokemonOverlayService;
import com.jhkim.reactionbot.service.ReactionOrchestrator;
import com.jhkim.reactionbot.service.ScreenCaptureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class SpeechController {

    private final ReactionOrchestrator orchestrator;
    private final ConversationHistory history;
    private final ScreenCaptureService screenCaptureService;
    private final PokemonOverlayService pokemonOverlayService;
    private final BotProperties properties;

    /**
     * 파이썬 STT 워커가 발화 한 덩어리 끝날 때마다 호출.
     * 봇이 말하고 있거나 grace period 중이면 무시되고 적절한 result 반환.
     */
    @PostMapping("/react/speech")
    public ResponseEntity<SpeechDto.Response> onSpeech(@RequestBody SpeechDto.Request request) {
        log.info("STT 발화 수신: '{}'", request.text());
        ReactionOrchestrator.ReactionOutcome outcome = orchestrator.onSpeech(request.text());
        return ResponseEntity.ok(new SpeechDto.Response(
                outcome.result().name(),
                outcome.botText()
        ));
    }

    /**
     * STT 워커가 발화 시작을 감지했을 때 호출.
     * 유저가 말하기 시작한 시점의 화면을 미리 캡처해두면
     * LLM이 실제 발화 맥락에 맞는 화면을 참고할 수 있다.
     */
    @PostMapping("/screen/pre-capture")
    public ResponseEntity<Void> preCapture() {
        screenCaptureService.preCaptureForSpeech();
        // overlay.mode=speech-precapture 면 발화 시점에 함께 분석. 응답 지연 안 주려고 비동기.
        BotProperties.Overlay ov = properties.getPokemon().getOverlay();
        if (properties.getPokemon().isEnabled()
                && ov.isEnabled()
                && "speech-precapture".equalsIgnoreCase(ov.getMode())) {
            CompletableFuture.runAsync(() -> {
                try { pokemonOverlayService.analyze(false); }
                catch (Exception e) { log.debug("speech-precapture overlay 분석 실패: {}", e.getMessage()); }
            });
        }
        return ResponseEntity.ok().build();
    }

    /** 대화 히스토리 초기화 (방송 시작 시) */
    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        history.clear();
        log.info("히스토리 초기화");
        return ResponseEntity.ok().build();
    }

    /** 상태 확인 */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "speaking", orchestrator.isSpeaking(),
                "historyTurns", history.snapshot().size()
        ));
    }
}
