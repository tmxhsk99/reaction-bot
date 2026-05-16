package com.jhkim.reactionbot.controller;

import com.jhkim.reactionbot.dto.SpeechDto;
import com.jhkim.reactionbot.service.ConversationHistory;
import com.jhkim.reactionbot.service.ReactionOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class SpeechController {

    private final ReactionOrchestrator orchestrator;
    private final ConversationHistory history;

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
