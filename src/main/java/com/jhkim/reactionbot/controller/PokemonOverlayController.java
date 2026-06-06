package com.jhkim.reactionbot.controller;

import com.jhkim.reactionbot.service.PokemonOverlayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 포켓몬 오버레이 데이터 엔드포인트. 정적 HTML(/pokemon-overlay)은 WebConfig에서 forward.
 *
 *  GET  /api/pokemon-overlay/state   : 현재 상태 (HTML이 폴링)
 *  POST /api/pokemon-overlay/analyze : 수동 분석 트리거 (manual 모드 버튼)
 */
@Slf4j
@RestController
@RequestMapping("/api/pokemon-overlay")
@RequiredArgsConstructor
public class PokemonOverlayController {

    private final PokemonOverlayService overlayService;

    @GetMapping("/state")
    public PokemonOverlayService.OverlayState state() {
        return overlayService.currentState();
    }

    @PostMapping("/analyze")
    public PokemonOverlayService.OverlayState analyze() {
        return overlayService.analyze(true);
    }
}
