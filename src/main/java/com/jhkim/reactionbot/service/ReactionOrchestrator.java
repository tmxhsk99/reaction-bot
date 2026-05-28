package com.jhkim.reactionbot.service;

import com.jhkim.reactionbot.config.BotProperties;
import com.jhkim.reactionbot.config.CharacterConfig;
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

    private final LlmProvider llmProvider;
    private final TtsService ttsService;
    private final ScreenCaptureService screenCaptureService;
    private final AvatarEventService avatarEvents;
    private final SpeechPrefilter prefilter;
    private final BotProperties properties;
    private final CharacterConfig character;

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

        // 5) "말 걸 때만" 모드 — 호명되지 않은 일반 발화는 LLM 호출 자체 안 함
        if (properties.getSpeech().isRespondOnlyWhenAddressed() && !directAddress) {
            log.debug("호명-전용 모드: 일반 발화 PASS ('{}')", text);
            return new ReactionOutcome(Result.PASS, null);
        }

        // 6) 발화 처리 시작 (lock)
        if (!speaking.compareAndSet(false, true)) {
            return new ReactionOutcome(Result.BUSY, null);
        }
        try {
            // 7) Triage — provider가 분리 호출을 지원하고, 호명되지 않은 경우에만 저렴한 모델로 1차 판단.
            //    multimodal-mode=ai-decide면 triage가 vision 필요 여부까지 함께 판단(SPEAK_VISION/SPEAK_TEXT).
            //    호명·단일-호출 provider는 triage 자체를 건너뜀.
            String mmMode = properties.getLlm().getMultimodalMode();
            boolean aiDecideVision = "ai-decide".equalsIgnoreCase(mmMode);
            boolean needsVision;  // 캡처할지 최종 결정 (always=true, never=false, ai-decide=triage 결과)

            if (llmProvider.hasSeparateTriage() && !directAddress) {
                LlmProvider.TriageResult triage = llmProvider.triage(text, aiDecideVision);
                if (triage == LlmProvider.TriageResult.PASS) {
                    log.info("PASS by triage (입력: '{}')", text);
                    return new ReactionOutcome(Result.PASS, null);
                }
                needsVision = (triage == LlmProvider.TriageResult.SPEAK_WITH_VISION);
            } else {
                // triage 안 한 경우 (호명 / single-call provider): mmMode만 보고 결정.
                // ai-decide여도 판단할 수 없으므로 안전하게 true(=always와 동일) 취급.
                needsVision = true;
                if (directAddress) log.info("호명 감지 - triage 건너뛰고 직행 (입력: '{}')", text);
                else               log.debug("provider 단일-호출 모드 - triage 생략 (입력: '{}')", text);
            }

            // 8) multimodal-mode 강제 오버라이드. always는 무조건 캡처, never는 무조건 미캡처.
            if ("always".equalsIgnoreCase(mmMode)) {
                needsVision = true;
            } else if ("never".equalsIgnoreCase(mmMode)) {
                needsVision = false;
            }

            // 9) 코멘트 생성 직전에 화면 캡처 (필요할 때만).
            //    text-only provider는 acceptsImage()=false라 캡처 자체를 건너뜀.
            String base64Image = null;
            if (needsVision && llmProvider.acceptsImage()) {
                try {
                    base64Image = screenCaptureService.captureBase64Jpeg();
                } catch (Exception e) {
                    log.warn("화면 캡처 실패. 이미지 없이 진행.", e);
                }
            } else if (!needsVision) {
                log.debug("vision 생략 (mode={}, triage 판단)", mmMode);
            }

            // 10) 실제 코멘트 생성
            String botText = llmProvider.generateComment(text, base64Image);
            if (LlmProvider.PASS.equals(botText) || botText == null || botText.isBlank()) {
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
     * Idle Trigger Scheduler가 호출. 유저/봇 침묵 조건은 scheduler가 이미 검증.
     * stage별로 다른 시스템 프롬프트 + 트리거 텍스트로 호출 (character.yml은 사용 X).
     *   LIGHT : 가볍게 한 마디 ("뭐 해?", "지루해~")
     *   TOPIC : 화면 보고 새 화제 던지기
     */
    public ReactionOutcome onIdleTrigger(BotProperties.IdleTrigger.Stage stage) {
        if (!speaking.compareAndSet(false, true)) {
            return new ReactionOutcome(Result.BUSY, null);
        }
        try {
            // vision provider일 때만 캡처. TOPIC은 화면 의존 비중 크지만 캡처 실패 시 텍스트로라도 시도.
            String base64Image = null;
            if (llmProvider.acceptsImage()) {
                try {
                    base64Image = screenCaptureService.captureBase64Jpeg();
                } catch (Exception e) {
                    log.warn("Idle trigger 화면 캡처 실패. 이미지 없이 진행.", e);
                }
            }

            String idleSystemPrompt;
            String triggerText;
            if (stage == BotProperties.IdleTrigger.Stage.TOPIC) {
                idleSystemPrompt = character.resolveIdleTopicPrompt();
                triggerText = "(능동 트리거 TOPIC: 한참 더 조용하네. 화면 보고 흥미로운 거 짚어서 새 화제 던져. 별거 없으면 PASS)";
            } else {
                idleSystemPrompt = character.resolveIdleLightPrompt();
                triggerText = "(능동 트리거 LIGHT: 한참 조용해. 가볍게 한 마디만. 별거 없으면 PASS)";
            }

            String botText = llmProvider.generateIdleComment(idleSystemPrompt, triggerText, base64Image);

            if (LlmProvider.PASS.equals(botText) || botText == null || botText.isBlank()) {
                log.info("Idle trigger PASS ({})", stage);
                return new ReactionOutcome(Result.PASS, null);
            }

            safeAvatarEvent(true);
            try {
                ttsService.speak(botText);
            } finally {
                safeAvatarEvent(false);
            }
            log.info("Idle trigger 발화 ({}): {}", stage, botText);
            return new ReactionOutcome(Result.SPOKE, botText);

        } catch (Exception e) {
            log.error("Idle trigger 처리 중 오류 ({})", stage, e);
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
