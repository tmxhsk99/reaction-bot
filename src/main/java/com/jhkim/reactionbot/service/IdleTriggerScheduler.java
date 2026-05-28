package com.jhkim.reactionbot.service;

import com.jhkim.reactionbot.config.BotProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 스트리머가 한참 조용할 때 봇이 먼저 말 걸도록 트리거. 2단계 ramping.
 *
 * 공통 조건:
 *  - reaction-bot.idle-trigger.enabled = true
 *  - 봇 마지막 발화로부터 min-since-bot-ms 이상 지남
 *  - 봇이 현재 발화 중이 아님
 *
 * 단계 결정 (유저 침묵 시간):
 *  - 유저 침묵 ≥ topicThresholdMs  → TOPIC 단계 (>0일 때만. 화면 기반 새 화제)
 *  - 유저 침묵 ≥ lightThresholdMs  → LIGHT 단계 (가벼운 한 마디)
 *  - 그 외                         → 미발동
 *
 * 단계 결정 후 orchestrator.onIdleTrigger(stage)에 위임.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdleTriggerScheduler {

    private final BotProperties properties;
    private final ReactionOrchestrator orchestrator;

    @PostConstruct
    public void init() {
        BotProperties.IdleTrigger cfg = properties.getIdleTrigger();
        if (cfg.isEnabled()) {
            log.info("Idle Trigger 활성 - light={}ms, topic={}ms (0=비활성), sinceBot={}ms, check={}ms",
                    cfg.getLightThresholdMs(),
                    cfg.getTopicThresholdMs(),
                    cfg.getMinSinceBotMs(),
                    cfg.getCheckIntervalMs());
        } else {
            log.info("Idle Trigger 비활성");
        }
    }

    @Scheduled(fixedRateString = "#{${reaction-bot.idle-trigger.check-interval-ms:20000}}",
            initialDelayString = "#{${reaction-bot.idle-trigger.check-interval-ms:20000}}")
    public void check() {
        BotProperties.IdleTrigger cfg = properties.getIdleTrigger();
        if (!cfg.isEnabled()) return;

        long sinceUser = orchestrator.msSinceLastUserUtterance();
        long sinceBot = orchestrator.msSinceLastBotSpoke();

        if (sinceBot < cfg.getMinSinceBotMs()) return;
        if (orchestrator.isSpeaking()) return;

        BotProperties.IdleTrigger.Stage stage = decideStage(cfg, sinceUser);
        if (stage == null) return;

        log.info("Idle Trigger 발동 [{}] (유저 {}s 침묵, 봇 {}s 침묵)",
                stage, sinceUser / 1000, sinceBot / 1000);
        orchestrator.onIdleTrigger(stage);
    }

    /**
     * 큰 임계값부터 검사해서 가장 격상된 단계 반환. null이면 미발동.
     * topic 임계값이 0 이하면 topic 단계 비활성 (light만).
     */
    private BotProperties.IdleTrigger.Stage decideStage(BotProperties.IdleTrigger cfg, long sinceUserMs) {
        if (cfg.getTopicThresholdMs() > 0 && sinceUserMs >= cfg.getTopicThresholdMs()) {
            return BotProperties.IdleTrigger.Stage.TOPIC;
        }
        if (sinceUserMs >= cfg.getLightThresholdMs()) {
            return BotProperties.IdleTrigger.Stage.LIGHT;
        }
        return null;
    }
}
