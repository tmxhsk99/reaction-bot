package com.jhkim.reactionbot.controller;

import com.jhkim.reactionbot.config.BotProperties;
import com.jhkim.reactionbot.service.FrameHashService;
import com.jhkim.reactionbot.service.ScreenCaptureService;
import com.jhkim.reactionbot.service.ScreenTranslateOrchestrator;
import com.jhkim.reactionbot.service.TranslationHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 화면 번역 모드 (reaction-bot.mode=screen-translate) 엔드포인트 집합.
 *
 *  - capture-sample : 영역 지정 UI 용 원본 PNG
 *  - state          : 현재 표시 중인 번역 박스 + 페이징 (SSE)
 *  - manual         : 수동 번역 트리거 (대상 언어면 무조건 번역)
 *  - page           : 다음/이전 페이지 (2줄 단위)
 *  - history        : 일자별 번역 로그 조회
 */
@Slf4j
@RestController
@RequestMapping("/api/translate")
@RequiredArgsConstructor
public class TranslateController {

    private final ScreenCaptureService screenCapture;
    private final ScreenTranslateOrchestrator orchestrator;
    private final TranslationHistoryService history;
    private final FrameHashService frameHash;
    private final BotProperties properties;

    /** 사용자가 /config 에서 "대화창 영역 지정" 누를 때 호출. PNG base64 + 원본 크기 반환. */
    @GetMapping("/capture-sample")
    public Map<String, Object> captureSample() {
        try {
            ScreenCaptureService.SampleCapture sample = screenCapture.captureSampleForSelection();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "ok");
            out.put("imageBase64Png", sample.base64Png());
            out.put("width", sample.width());
            out.put("height", sample.height());
            return out;
        } catch (Exception e) {
            log.warn("capture-sample 실패", e);
            return Map.of("status", "error", "message", safeErr(e));
        }
    }

    /** 현재 상태 (즉시 한 번). HTML 첫 로드 시 사용. SSE 도 함께 제공. */
    @GetMapping("/state")
    public ScreenTranslateOrchestrator.TranslateState state() {
        return orchestrator.currentState();
    }

    /**
     * SSE 스트림 — orchestrator 가 상태 변경 시 push.
     * /translate UI 가 EventSource 로 구독 → 실시간 업데이트.
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return orchestrator.subscribe();
    }

    /** 수동 번역 트리거. 자동/수동 모드 무관하게 작동 (수동 모드는 이게 유일한 진입점). */
    @PostMapping("/manual")
    public Map<String, Object> manual() {
        try {
            orchestrator.triggerManual();
            return Map.of("status", "ok", "message", "번역 요청 큐잉됨");
        } catch (Exception e) {
            log.warn("manual 번역 트리거 실패", e);
            return Map.of("status", "error", "message", safeErr(e));
        }
    }

    /** 페이지 이동. direction = "next" | "prev". */
    @PostMapping("/page")
    public ScreenTranslateOrchestrator.TranslateState page(@RequestParam("direction") String direction) {
        orchestrator.changePage(direction);
        return orchestrator.currentState();
    }

    /** 자동 번역 런타임 토글. cfg.autoMode=false 면 무관(항상 false). 반환=토글 후 상태. */
    @PostMapping("/auto-toggle")
    public Map<String, Object> autoToggle() {
        boolean now = orchestrator.toggleAuto();
        return Map.of("status", "ok", "autoRuntime", now);
    }

    /** 최근 번역 ID 로 TTS 재생. 히스토리/state 영향 없음 — 단순 발화. */
    @PostMapping("/replay")
    public Map<String, Object> replay(@RequestParam("id") String id) {
        boolean ok = orchestrator.replayById(id);
        if (!ok) return Map.of("status", "error", "message", "해당 ID 의 최근 번역이 없습니다(만료 또는 미존재)");
        return Map.of("status", "ok");
    }

    /** 일자 리스트 (별칭 포함). 최근 날짜부터. */
    @GetMapping("/history")
    public List<TranslationHistoryService.DateSummary> historyList() {
        return history.listDates();
    }

    /** 해당 일자의 모든 번역 엔트리 (시간순). */
    @GetMapping("/history/{date}")
    public List<TranslationHistoryService.HistoryEntry> historyForDate(@PathVariable("date") String date) {
        return history.entriesFor(date);
    }

    /** 파이프라인 진단 — 마지막 runOnce 가 어디서 멈췄는지, 카운터, raw 응답 일부. */
    @GetMapping("/debug/pipeline")
    public ScreenTranslateOrchestrator.PipelineDiag debugPipeline() {
        return orchestrator.currentDiag();
    }

    /**
     * 캡처 진단. /translate/debug 페이지가 1.5s 폴링. 현재 캡처(crop 적용) 이미지 + 통계 +
     * dHash 반환. mean=0, stddev=0 가 계속 뜨면 OBS 미연결/잘못된 씬/모니터 인덱스 문제.
     */
    @GetMapping("/debug/capture")
    public Map<String, Object> debugCapture() {
        BotProperties.ScreenTranslate cfg = properties.getScreenTranslate();
        String crop = "region".equalsIgnoreCase(cfg.getCaptureMode()) ? cfg.getCropRegion() : "";
        try {
            // 캡처 한 번만 — 같은 image 로 dhash + PNG 모두 만들어 OBS 동시 호출/Send pending 회피.
            ScreenCaptureService.TranslateCapture cap = screenCapture.captureForTranslate(crop, cfg.getBlankLumaStddev(), cfg.getTargetWidth());
            long dhash = frameHash.dhash(cap.image());
            String pngBase64;
            try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
                javax.imageio.ImageIO.write(cap.image(), "png", baos);
                pngBase64 = java.util.Base64.getEncoder().encodeToString(baos.toByteArray());
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "ok");
            out.put("source", properties.getScreen().getSource());
            out.put("captureMode", cfg.getCaptureMode());
            out.put("cropRegion", crop);
            out.put("rawWidth", cap.rawWidth());
            out.put("rawHeight", cap.rawHeight());
            out.put("processedWidth", cap.processedWidth());
            out.put("processedHeight", cap.processedHeight());
            out.put("lumaMean", cap.lumaMean());
            out.put("lumaStddev", cap.lumaStddev());
            out.put("blankThreshold", cap.blankThreshold());
            out.put("blank", cap.blank());
            out.put("dhashHex", String.format("%016x", dhash));
            out.put("processedImageBase64Png", pngBase64);
            return out;
        } catch (Exception e) {
            log.warn("debug capture 실패", e);
            return Map.of("status", "error", "message", safeErr(e));
        }
    }

    /** 별칭 지정/변경. body: {"alias":"..."} . 빈 문자열이면 별칭 제거. */
    @PutMapping("/history/{date}/alias")
    public Map<String, Object> setAlias(@PathVariable("date") String date,
                                        @RequestBody Map<String, Object> body) {
        String alias = body.get("alias") == null ? "" : String.valueOf(body.get("alias")).trim();
        history.setAlias(date, alias);
        return Map.of("status", "ok", "date", date, "alias", alias);
    }

    /** Map.of 는 null 값을 거부 → e.getMessage() 가 null 일 때 폴백. */
    private static String safeErr(Throwable e) {
        String m = e.getMessage();
        return (m == null || m.isBlank()) ? e.getClass().getSimpleName() : m;
    }
}
