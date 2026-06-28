package com.jhkim.reactionbot.controller;

import com.jhkim.reactionbot.service.PokemonOverlayService;
import com.jhkim.reactionbot.service.PokemonSpeciesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private final PokemonSpeciesService speciesService;

    @GetMapping("/state")
    public PokemonOverlayService.OverlayState state() {
        return overlayService.currentState();
    }

    @PostMapping("/analyze")
    public PokemonOverlayService.OverlayState analyze() {
        log.info("수동 분석 요청 수신");
        PokemonOverlayService.OverlayState s = overlayService.analyze(true);
        log.info("수동 분석 응답: cards={}, mirror={}, lastError='{}'",
                s.cards() == null ? 0 : s.cards().size(), s.mirror(), s.lastError());
        return s;
    }

    /**
     * 수동 입력으로 카드 채우기 (LLM 인식 실패 시 fallback).
     * body 예: {"names": ["한카리아스", "잠만보"]}  또는  {"names": "한카리아스, 잠만보"}
     */
    @PostMapping("/manual")
    public PokemonOverlayService.OverlayState manual(@RequestBody Map<String, Object> body) {
        List<String> names = new ArrayList<>();
        Object raw = body == null ? null : body.get("names");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) names.add(o.toString());
            }
        } else if (raw instanceof String s) {
            for (String p : s.split(",")) {
                String t = p.trim();
                if (!t.isEmpty()) names.add(t);
            }
        }
        log.info("수동 입력 요청 수신: {}", names);
        return overlayService.applyManual(names);
    }

    /** 카드 비우기 (수동 초기화). */
    @DeleteMapping("/cards")
    public PokemonOverlayService.OverlayState clearCards() {
        log.info("카드 초기화 요청 수신");
        return overlayService.clearCards();
    }

    /**
     * 자동완성 후보. 클라이언트가 입력 도중 디바운스로 호출.
     * 인덱스 워밍업 안 됐으면 빈 리스트 (조용히 폴백).
     */
    @GetMapping("/suggest")
    public List<String> suggest(@RequestParam("q") String q,
                                @RequestParam(value = "limit", defaultValue = "8") int limit) {
        return speciesService.suggest(q, limit);
    }

    /**
     * 일어 인덱스 강제 재빌드 트리거. 새 세대 출시 등 PokéAPI 데이터 갱신이 필요할 때.
     * 비동기 — 빌드 중에도 옛 인덱스로 자동완성/lookup 계속 동작.
     */
    @PostMapping("/rebuild-index")
    public Map<String, String> rebuildIndex() {
        log.info("인덱스 재빌드 요청 수신");
        speciesService.triggerRebuild();
        return Map.of("status", "started",
                "message", "백그라운드 재빌드 시작. 수십 초 후 ./data/pokemon-name-index.json 갱신.");
    }

    /** 인덱스 빌드 상태 — UI 가 폴링해서 "재빌드 중…" / "{N}개" 표시. */
    @GetMapping("/index-status")
    public PokemonSpeciesService.IndexStatus indexStatus() {
        return speciesService.indexStatus();
    }
}
