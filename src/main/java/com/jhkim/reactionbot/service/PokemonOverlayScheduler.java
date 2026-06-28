package com.jhkim.reactionbot.service;

import com.jhkim.reactionbot.config.BotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * overlay.mode=auto 일 때 refresh-interval-ms 마다 분석 호출.
 * 다른 모드(manual/speech-precapture)에서는 no-op.
 *
 * 고정 1초 주기로 깨어나서 properties 의 refreshIntervalMs 와 비교 — 설정이 바뀌어도
 * 즉시 반영. analyze() 자체가 in-flight 잠금을 갖고 있어 중복 트리거 안전.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PokemonOverlayScheduler {

    private final BotProperties properties;
    private final PokemonOverlayService overlayService;

    private final AtomicReference<Instant> lastRun = new AtomicReference<>(Instant.EPOCH);

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        if (properties.isScreenTranslateMode()) return;
        BotProperties.Overlay cfg = properties.getPokemon().getOverlay();
        if (!properties.getPokemon().isEnabled() || !cfg.isEnabled()) return;
        if (!"auto".equalsIgnoreCase(cfg.getMode())) return;

        long intervalMs = Math.max(1000L, cfg.getRefreshIntervalMs());
        Instant prev = lastRun.get();
        if (java.time.Duration.between(prev, Instant.now()).toMillis() < intervalMs) return;
        lastRun.set(Instant.now());

        try {
            overlayService.analyze(false);
        } catch (Exception e) {
            log.debug("auto 분석 tick 실패: {}", e.getMessage());
        }
    }
}
