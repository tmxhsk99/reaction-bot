package com.jhkim.reactionbot.service;

import com.jhkim.reactionbot.config.BotProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 스트리머가 한참 조용할 때 봇이 먼저 말 걸도록 트리거.
 *
 * 조건 (모두 만족):
 *  - reaction-bot.idle-trigger.enabled = true
 *  - 유저 마지막 발화로부터 silence-threshold-ms 이상 지남
 *  - 봇 마지막 발화로부터 min-since-bot-ms 이상 지남
 *  - 봇이 현재 발화 중이 아님
 *
 * 만족하면 orchestrator.onIdleTrigger() 호출. 거기서 Claude가 PASS 또는 능동 코멘트 결정.
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
            log.info("Idle Trigger 활성 - silence={}ms, sinceBot={}ms, check={}ms",
                    cfg.getSilenceThresholdMs(),
                    cfg.getMinSinceBotMs(),
                    cfg.getCheckIntervalMs());
        } else {
            log.info("Idle Trigger 비활성");
        }
    }

    /**
     * fixedRateString을 SpEL로 application.yml에서 읽음.
     * 비활성 시에는 일찍 return해서 비용 없음.
     */
    @Scheduled(fixedRateString = "#{${reaction-bot.idle-trigger.check-interval-ms:20000}}",
            initialDelayString = "#{${reaction-bot.idle-trigger.check-interval-ms:20000}}")
    public void check() {
        BotProperties.IdleTrigger cfg = properties.getIdleTrigger();
        if (!cfg.isEnabled()) return;

        long sinceUser = orchestrator.msSinceLastUserUtterance();
        long sinceBot = orchestrator.msSinceLastBotSpoke();

        if (sinceUser < cfg.getSilenceThresholdMs()) return;
        if (sinceBot < cfg.getMinSinceBotMs()) return;
        if (orchestrator.isSpeaking()) return;

        log.info("Idle Trigger 발동 (유저 {}s 침묵, 봇 {}s 침묵)",
                sinceUser / 1000, sinceBot / 1000);
        orchestrator.onIdleTrigger();
    }
}
