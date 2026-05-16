package com.jhkim.reactionbot.service;

import com.jhkim.reactionbot.config.BotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 발화 입력을 받아서 Claude 호출 → TTS → 재생까지 조율.
 *
 * 피드백 루프 방지:
 * - 봇이 말하는 동안 들어온 발화는 무시
 * - 봇 발화가 끝난 뒤 grace period 동안 추가로 무시 (스피커 잔향 컷)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReactionOrchestrator {

    private final ClaudeService claudeService;
    private final TtsService ttsService;
    private final ScreenCaptureService screenCaptureService;
    private final AvatarEventService avatarEvents;
    private final SpeechPrefilter prefilter;
    private final BotProperties properties;

    private final AtomicBoolean speaking = new AtomicBoolean(false);
    private final AtomicReference<Instant> spokeEndedAt = new AtomicReference<>(Instant.EPOCH);
    private final AtomicReference<Instant> lastUserUtteranceAt = new AtomicReference<>(Instant.now());

    public enum Result {
        SPOKE,
        PASS,
        BUSY,
        GRACE,
        COOLDOWN,
        TOO_SHORT,
        FILTERED,    // prefilter에서 노이즈로 컷
        ERROR
    }

    public ReactionOutcome onSpeech(String text) {
        // 유저가 뭔가 말했다는 사실은 항상 기록 (idle trigger용)
        if (text != null && !text.trim().isEmpty()) {
            lastUserUtteranceAt.set(Instant.now());
        }

        // 1) 길이 컷
        if (text == null || text.trim().length() < properties.getSpeech().getMinTextLength()) {
            return new ReactionOutcome(Result.TOO_SHORT, null);
        }

        // 2) 봇 발화 중이면 무시
        if (properties.getSpeech().isIgnoreDuringSpeech() && speaking.get()) {
            return new ReactionOutcome(Result.BUSY, null);
        }

        // 3) grace period (스피커 잔향 컷)
        long sinceEnd = Duration.between(spokeEndedAt.get(), Instant.now()).toMillis();
        if (sinceEnd < properties.getSpeech().getGracePeriodMs()) {
            return new ReactionOutcome(Result.GRACE, null);
        }

        // 4) prefilter — 호명 감지 또는 노이즈 컷
        SpeechPrefilter.Decision decision = prefilter.decide(text);
        if (decision == SpeechPrefilter.Decision.FILTER_OUT) {
            log.debug("prefilter 컷 (노이즈): '{}'", text);
            return new ReactionOutcome(Result.FILTERED, null);
        }
        boolean directAddress = (decision == SpeechPrefilter.Decision.DIRECT_ADDRESS);

        // 5) 쿨다운 — 호명된 경우는 무시 (사용자가 부르면 응답)
        if (!directAddress && sinceEnd < properties.getSpeech().getCooldownMs()) {
            log.debug("쿨다운 {}ms 중 (호명 아님). 컷: '{}'", sinceEnd, text);
            return new ReactionOutcome(Result.COOLDOWN, null);
        }

        // 6) 발화 처리 시작 (lock)
        if (!speaking.compareAndSet(false, true)) {
            return new ReactionOutcome(Result.BUSY, null);
        }
        try {
            String base64Image = null;
            try {
                base64Image = screenCaptureService.captureBase64Jpeg();
            } catch (Exception e) {
                log.warn("화면 캡처 실패. 이미지 없이 진행.", e);
            }

            // 7) Triage — 호명되지 않았으면 Haiku로 PASS/SPEAK 먼저 결정 (토큰 절약)
            if (!directAddress) {
                boolean speak = claudeService.triage(text, base64Image);
                if (!speak) {
                    log.info("PASS by triage (입력: '{}')", text);
                    return new ReactionOutcome(Result.PASS, null);
                }
            } else {
                log.info("호명 감지 - triage 건너뛰고 직행 (입력: '{}')", text);
            }

            // 8) Sonnet으로 실제 코멘트 생성
            String botText = claudeService.generateComment(text, base64Image);
            if (ClaudeService.PASS.equals(botText) || botText == null || botText.isBlank()) {
                return new ReactionOutcome(Result.PASS, null);
            }

            safeAvatarEvent(true);
            try {
                ttsService.speak(botText);
            } finally {
                safeAvatarEvent(false);
            }
            return new ReactionOutcome(Result.SPOKE, botText);

        } catch (Exception e) {
            log.error("발화 처리 중 오류", e);
            return new ReactionOutcome(Result.ERROR, null);
        } finally {
            spokeEndedAt.set(Instant.now());
            speaking.set(false);
        }
    }

    public boolean isSpeaking() {
        return speaking.get();
    }

    public long msSinceLastUserUtterance() {
        return Duration.between(lastUserUtteranceAt.get(), Instant.now()).toMillis();
    }

    public long msSinceLastBotSpoke() {
        return Duration.between(spokeEndedAt.get(), Instant.now()).toMillis();
    }

    /**
     * Idle Trigger Scheduler가 호출. 유저 침묵 + 봇 침묵 조건을 이미 검증한 상태.
     * triage는 건너뛰고 Sonnet에게 "능동 트리거"로 한 마디 시도하게 함. (PASS 가능)
     */
    public ReactionOutcome onIdleTrigger() {
        if (!speaking.compareAndSet(false, true)) {
            return new ReactionOutcome(Result.BUSY, null);
        }
        try {
            String base64Image = null;
            try {
                base64Image = screenCaptureService.captureBase64Jpeg();
            } catch (Exception e) {
                log.warn("Idle trigger 화면 캡처 실패. 스킵.", e);
                return new ReactionOutcome(Result.ERROR, null);
            }

            // 능동 트리거 — Sonnet에게 "조용했음. 뭔가 한 마디 해 봐. 별거 없으면 PASS"
            String triggerText = "(능동 트리거: 한참 조용했어. 화면 보고 가볍게 말 걸거나 질문 던져 봐. 별거 없으면 PASS)";
            String botText = claudeService.generateComment(triggerText, base64Image);

            if (ClaudeService.PASS.equals(botText) || botText == null || botText.isBlank()) {
                log.info("Idle trigger PASS");
                return new ReactionOutcome(Result.PASS, null);
            }

            safeAvatarEvent(true);
            try {
                ttsService.speak(botText);
            } finally {
                safeAvatarEvent(false);
            }
            log.info("Idle trigger 발화: {}", botText);
            return new ReactionOutcome(Result.SPOKE, botText);

        } catch (Exception e) {
            log.error("Idle trigger 처리 중 오류", e);
            return new ReactionOutcome(Result.ERROR, null);
        } finally {
            spokeEndedAt.set(Instant.now());
            speaking.set(false);
        }
    }

    /** SSE 이벤트 발행은 부가 기능이므로 어떤 예외도 메인 흐름을 깨지 못하게 격리. */
    private void safeAvatarEvent(boolean start) {
        try {
            if (start) avatarEvents.speakStart();
            else       avatarEvents.speakEnd();
        } catch (Throwable t) {
            log.debug("아바타 이벤트 발행 실패 ({}): {}", start ? "start" : "end", t.getMessage());
        }
    }

    public record ReactionOutcome(Result result, String botText) {}
}
