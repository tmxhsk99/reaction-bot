package com.jhkim.reactionbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhkim.reactionbot.config.BotProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 화면 번역 모드 핵심 파이프라인.
 *
 * 흐름 (auto mode, 토글 ON):
 *   tick → capture → 2-프레임 안정성 체크 (직전 프레임과 비슷할 때만 통과)
 *        → lastStable 과 다를 때만 stage 1 호출
 *        → stage 1 (triage 모델, vision) → SKIP 또는 {speaker, source, lang}
 *        → source/translated 유사도 dedup
 *        → stage 2 (메인 모델, text) → translated
 *        → 상태/SSE/히스토리/TTS 큐 (latest-wins)
 *
 * 흐름 (manual / 토글 OFF):
 *   manual=true 면 force → 안정성/dedup 우회. 토글 OFF 면 tick 자체 no-op.
 *
 * 최근 번역 버퍼:
 *   메모리 ring buffer (히스토리 파일과 별개) — UI 재생 버튼이 ID로 참조.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScreenTranslateOrchestrator {

    private final BotProperties properties;
    private final ScreenCaptureService screenCapture;
    private final LlmProvider llmProvider;
    private final FrameHashService frameHash;
    private final TranslationHistoryService historyService;
    private final TtsService ttsService;
    private final AvatarEventService avatarEvents;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ----- 상태 -----
    private final AtomicReference<TranslateEntry> currentEntry = new AtomicReference<>(null);
    private final AtomicInteger pageIndex = new AtomicInteger(0);
    private final AtomicReference<String> lastSource = new AtomicReference<>("");
    private final AtomicReference<String> lastTranslated = new AtomicReference<>("");
    private final AtomicBoolean translating = new AtomicBoolean(false);
    private final AtomicReference<Instant> lastRun = new AtomicReference<>(Instant.EPOCH);
    private final AtomicReference<String> lastError = new AtomicReference<>(null);

    // ----- 해시 안정성 추적 -----
    private final AtomicLong lastFrameHash = new AtomicLong(0);
    private final AtomicBoolean hasLastFrameHash = new AtomicBoolean(false);
    private final AtomicLong lastStableHash = new AtomicLong(0);
    private final AtomicBoolean hasLastStableHash = new AtomicBoolean(false);

    // ----- 자동 토글 (런타임) -----
    // cfg.autoMode=false 면 그 자체로 비활성. cfg.autoMode=true 일 때 사용자가 일시 토글.
    private final AtomicBoolean autoRuntimeEnabled = new AtomicBoolean(true);

    // ----- TTS 큐잉 (latest-wins) -----
    private final AtomicReference<String> pendingTts = new AtomicReference<>(null);
    private final Semaphore ttsSignal = new Semaphore(0);
    private Thread ttsThread;

    // ----- 최근 번역 버퍼 (UI 재생 버튼) -----
    private final Deque<TranslateEntry> recent = new ArrayDeque<>();
    private final Object recentLock = new Object();

    // ----- 파이프라인 진단 (디버그 페이지용) -----
    private final AtomicReference<PipelineDiag> lastDiag = new AtomicReference<>(null);
    private final java.util.concurrent.atomic.AtomicLong tickCount = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong runCount = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong stage1Calls = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong stage2Calls = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong entriesProduced = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong ttsEnqueuedCount = new java.util.concurrent.atomic.AtomicLong();

    /**
     * runOnce 한 번의 상세 진단. /translate/debug 가 폴링.
     *  stage: 어디서 멈췄는지 ("ok" 면 끝까지 진행됨)
     *  reason: 사람이 읽을 메시지
     *  stage1RawPreview/stage2RawPreview: LLM raw 응답 앞 200자
     */
    public record PipelineDiag(
            String ts,
            boolean tickEntered,
            boolean modeOk, boolean autoCfg, boolean autoRuntime,
            String dhashHex,
            Integer hammingFromPrev,
            Integer hammingFromStable,
            String stage,
            String reason,
            String stage1RawPreview,
            String stage1Speaker, String stage1Source, String stage1Lang,
            String stage2RawPreview,
            long tickCount, long runCount,
            long stage1Calls, long stage2Calls,
            long entriesProduced, long ttsEnqueued
    ) {}

    public PipelineDiag currentDiag() {
        PipelineDiag d = lastDiag.get();
        if (d != null) return d;
        // 첫 호출 전이면 카운터만 채워 반환
        return new PipelineDiag(null, false,
                properties.isScreenTranslateMode(),
                properties.getScreenTranslate().isAutoMode(),
                autoRuntimeEnabled.get(),
                null, null, null,
                "idle", "아직 한 번도 runOnce 진입 전 (또는 캡처 대기)",
                null, null, null, null, null,
                tickCount.get(), runCount.get(),
                stage1Calls.get(), stage2Calls.get(),
                entriesProduced.get(), ttsEnqueuedCount.get());
    }

    private void recordDiag(String stage, String reason,
                            String dhashHex, Integer hPrev, Integer hStable,
                            String s1Raw, ParsedTriage s1Parsed, String s2Raw) {
        lastDiag.set(new PipelineDiag(
                Instant.now().toString(),
                true,
                properties.isScreenTranslateMode(),
                properties.getScreenTranslate().isAutoMode(),
                autoRuntimeEnabled.get(),
                dhashHex, hPrev, hStable,
                stage, reason,
                preview(s1Raw, 200),
                s1Parsed == null ? null : s1Parsed.speaker,
                s1Parsed == null ? null : s1Parsed.source,
                s1Parsed == null ? null : s1Parsed.lang,
                preview(s2Raw, 200),
                tickCount.get(), runCount.get(),
                stage1Calls.get(), stage2Calls.get(),
                entriesProduced.get(), ttsEnqueuedCount.get()));
    }

    private static String preview(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.length() <= max) return t;
        return t.substring(0, max) + "…";
    }

    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService runExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "translate-run");
                t.setDaemon(true);
                return t;
            });

    public record TranslateEntry(
            String id,
            String ts,
            String speaker,
            String source,
            String translated,
            List<String> lines,
            String sourceLang,
            String targetLang
    ) {}

    public record TranslateState(
            boolean enabled,
            boolean autoMode,
            boolean autoRuntime,
            String captureMode,
            String cropRegion,
            String lastError,
            TranslateEntry entry,
            int pageIndex,
            int totalPages,
            int linesPerPage,
            List<TranslateEntry> recent
    ) {}

    @PostConstruct
    public void init() {
        ttsThread = new Thread(this::ttsLoop, "translate-tts");
        ttsThread.setDaemon(true);
        ttsThread.start();
    }

    @PreDestroy
    public void shutdown() {
        runExecutor.shutdownNow();
        if (ttsThread != null) ttsThread.interrupt();
    }

    public TranslateState currentState() {
        BotProperties.ScreenTranslate cfg = properties.getScreenTranslate();
        TranslateEntry e = currentEntry.get();
        int linesPerPage = Math.max(1, cfg.getLinesPerPage());
        int total = (e == null || e.lines().isEmpty()) ? 0
                : (int) Math.ceil(e.lines().size() / (double) linesPerPage);
        int idx = Math.max(0, Math.min(pageIndex.get(), Math.max(0, total - 1)));
        return new TranslateState(
                properties.isScreenTranslateMode(),
                cfg.isAutoMode(),
                autoRuntimeEnabled.get(),
                cfg.getCaptureMode(),
                cfg.getCropRegion(),
                lastError.get(),
                e,
                idx,
                total,
                linesPerPage,
                snapshotRecent());
    }

    public SseEmitter subscribe() {
        SseEmitter em = new SseEmitter(0L);
        subscribers.add(em);
        em.onCompletion(() -> subscribers.remove(em));
        em.onTimeout(() -> subscribers.remove(em));
        em.onError(t -> subscribers.remove(em));
        try {
            em.send(SseEmitter.event().name("state").data(currentState()));
        } catch (Exception ex) {
            subscribers.remove(em);
        }
        return em;
    }

    public void changePage(String direction) {
        TranslateEntry e = currentEntry.get();
        if (e == null) return;
        BotProperties.ScreenTranslate cfg = properties.getScreenTranslate();
        int linesPerPage = Math.max(1, cfg.getLinesPerPage());
        int total = (int) Math.ceil(e.lines().size() / (double) linesPerPage);
        int idx = pageIndex.get();
        if ("next".equalsIgnoreCase(direction)) idx = Math.min(total - 1, idx + 1);
        else if ("prev".equalsIgnoreCase(direction)) idx = Math.max(0, idx - 1);
        pageIndex.set(idx);
        broadcast();
    }

    /** 수동 트리거 — prefilter/dialogue-only 우회. screen-translate 모드 아니면 거부. */
    public void triggerManual() {
        if (!properties.isScreenTranslateMode()) {
            lastError.set("screen-translate 모드가 아닙니다. /config 에서 모드 전환 후 재기동하세요.");
            broadcast();
            return;
        }
        runExecutor.execute(() -> runOnce(true));
    }

    /** 런타임 자동 토글. 반환은 effective 상태 — cfg.autoMode=false 면 항상 false. */
    public boolean toggleAuto() {
        boolean now = !autoRuntimeEnabled.get();
        autoRuntimeEnabled.set(now);
        broadcast();
        return properties.getScreenTranslate().isAutoMode() && now;
    }

    /** 명시적 set. */
    public void setAutoRuntime(boolean enabled) {
        autoRuntimeEnabled.set(enabled);
        broadcast();
    }

    /** 최근 번역을 ID 로 찾아 TTS 재생. UI 의 ▶ 재생 버튼. */
    public boolean replayById(String id) {
        if (id == null) return false;
        synchronized (recentLock) {
            for (TranslateEntry e : recent) {
                if (id.equals(e.id())) {
                    String visible = visibleTextOnPageZero(e, properties.getScreenTranslate().getLinesPerPage());
                    if (visible.isBlank()) visible = e.translated();
                    enqueueTts(visible);
                    return true;
                }
            }
        }
        return false;
    }

    @Scheduled(fixedDelay = 500)
    public void tick() {
        tickCount.incrementAndGet();
        if (!properties.isScreenTranslateMode()) {
            recordDiag("mode-off", "mode != screen-translate", null, null, null, null, null, null);
            return;
        }
        BotProperties.ScreenTranslate cfg = properties.getScreenTranslate();
        if (!cfg.isAutoMode()) {
            recordDiag("auto-cfg-off", "cfg.autoMode = false (수동 전용 모드)", null, null, null, null, null, null);
            return;
        }
        if (!autoRuntimeEnabled.get()) {
            recordDiag("auto-runtime-off", "사용자가 자동 토글 OFF", null, null, null, null, null, null);
            return;
        }
        long intervalMs = Math.max(300L, cfg.getIntervalMs());
        Instant prev = lastRun.get();
        if (java.time.Duration.between(prev, Instant.now()).toMillis() < intervalMs) return;
        lastRun.set(Instant.now());
        runOnce(false);
    }

    /**
     * 메인 파이프라인.
     * force=true 면 안정성 체크 / dedup 우회 (수동 트리거).
     */
    private void runOnce(boolean force) {
        if (!translating.compareAndSet(false, true)) {
            recordDiag("busy", "직전 runOnce 진행 중 — 스킵", null, null, null, null, null, null);
            return;
        }
        runCount.incrementAndGet();
        try {
            BotProperties.ScreenTranslate cfg = properties.getScreenTranslate();

            // ----- 캡처 -----
            ScreenCaptureService.TranslateCapture cap;
            try {
                String crop = "region".equalsIgnoreCase(cfg.getCaptureMode()) ? cfg.getCropRegion() : "";
                cap = screenCapture.captureForTranslate(crop, cfg.getBlankLumaStddev(), cfg.getTargetWidth());
            } catch (Exception e) {
                lastError.set("화면 캡처 실패: " + e.getMessage());
                log.warn("화면 번역: 캡처 실패", e);
                recordDiag("capture-fail", "캡처 실패: " + e.getMessage(), null, null, null, null, null, null);
                broadcast();
                return;
            }
            if (cap.blank() || cap.base64Jpeg() == null) {
                recordDiag("blank", "단색/blank 판정 (mean=" + String.format("%.1f", cap.lumaMean())
                        + ", stddev=" + String.format("%.1f", cap.lumaStddev())
                        + ", 임계=" + cap.blankThreshold() + ")",
                        null, null, null, null, null, null);
                return;
            }

            // ----- dHash 안정성 체크 (수동이면 우회) -----
            long currentHash = frameHash.dhash(cap.image());
            String hashHex = String.format("%016x", currentHash);
            int thresh = Math.max(0, cfg.getHashStabilityThreshold());
            Integer dPrev = null, dStable = null;
            if (!force) {
                // 첫 프레임이면 기록만
                if (!hasLastFrameHash.getAndSet(true)) {
                    lastFrameHash.set(currentHash);
                    recordDiag("first-frame", "첫 프레임 기록만 (다음 tick 부터 안정성 비교)",
                            hashHex, null, null, null, null, null);
                    return;
                }
                long prevFrame = lastFrameHash.get();
                int distFromPrev = frameHash.hamming(currentHash, prevFrame);
                dPrev = distFromPrev;
                lastFrameHash.set(currentHash);

                // (1) 화면 이동/변화 중 — 직전 프레임과 너무 다르면 stable 아님 → 호출 X
                if (cfg.isRequireFrameStability() && distFromPrev > thresh) {
                    recordDiag("movement-skip",
                            "프레임 변화 중 — hamming(prev)=" + distFromPrev + " > 임계 " + thresh
                                    + ". 화면이 안정되길 기다림. (이동·전투·애니메이션 중일 가능성)",
                            hashHex, distFromPrev, null, null, null, null);
                    return;
                }

                // (2) 이미 처리한 안정 상태와 동일 — 중복 → 호출 X
                if (hasLastStableHash.get()) {
                    int distFromStable = frameHash.hamming(currentHash, lastStableHash.get());
                    dStable = distFromStable;
                    if (distFromStable <= thresh) {
                        recordDiag("same-stable",
                                "이미 처리한 안정 상태와 동일 — hamming(stable)=" + distFromStable + " ≤ " + thresh,
                                hashHex, distFromPrev, distFromStable, null, null, null);
                        return;
                    }
                }
                // 새 안정 상태 진입 — lastStableHash 는 stage 1/2 결과를 확정한 뒤 commit.
                // (LLM 호출이 transient 실패하면 같은 프레임을 다음 tick 에서 다시 시도 가능)
            } else {
                // 수동이면 이후 hash 비교의 기준점 갱신
                lastFrameHash.set(currentHash);
                lastStableHash.set(currentHash);
                hasLastFrameHash.set(true);
                hasLastStableHash.set(true);
            }

            // ----- Stage 1: triage (vision) -----
            ParsedTriage triage;
            String stage1Raw = null;
            try {
                stage1Calls.incrementAndGet();
                stage1Raw = llmProvider.analyzeImage(
                        buildStage1SystemPrompt(cfg, force),
                        buildStage1UserPrompt(),
                        cap.base64Jpeg(),
                        true);
                triage = parseStage1(stage1Raw);
            } catch (UnsupportedOperationException uoe) {
                lastError.set("현재 LLM provider(" + properties.getLlm().getProvider()
                        + ")는 화면 번역(analyzeImage)을 지원하지 않습니다.");
                log.warn(lastError.get());
                recordDiag("stage1-unsupported", lastError.get(), hashHex, dPrev, dStable, null, null, null);
                broadcast();
                return;
            } catch (Exception e) {
                lastError.set("Stage 1 (triage) 실패: " + e.getMessage());
                log.warn("화면 번역: stage 1 실패", e);
                recordDiag("stage1-fail", "Stage 1 LLM 호출 실패: " + e.getMessage(),
                        hashHex, dPrev, dStable, null, null, null);
                broadcast();
                return;
            }
            if (triage == null) {
                // 이 프레임은 LLM 이 SKIP 으로 확정 — 같은 프레임 재시도 무의미. stable commit.
                commitStableHash(currentHash, force);
                recordDiag("stage1-skip",
                        "Stage 1 응답이 SKIP — 대상 언어가 아니거나 (현재 source-langs=" + cfg.getSourceLangs()
                                + ") 대화창 텍스트가 아니라고 LLM이 판단. dialogue-only=" + cfg.isDialogueOnly()
                                + ". 원시 응답 위 stage1RawPreview 확인.",
                        hashHex, dPrev, dStable, stage1Raw, null, null);
                return;
            }

            // ----- source 유사도 dedup (수동이면 우회) -----
            if (!force) {
                String normSrc = normalizeForDedup(triage.source);
                String normLastSrc = normalizeForDedup(lastSource.get());
                double srcSim = similarityRatio(normSrc, normLastSrc);
                if (srcSim >= cfg.getTranslationDedupSimilarity()) {
                    commitStableHash(currentHash, force);
                    recordDiag("source-dedup",
                            "Stage 1 추출한 source 가 직전과 유사도 " + String.format("%.2f", srcSim)
                                    + " ≥ " + cfg.getTranslationDedupSimilarity() + " — stage 2 스킵",
                            hashHex, dPrev, dStable, stage1Raw, triage, null);
                    return;
                }
            }

            // ----- Stage 2: 번역 (text) -----
            String stage2Raw = null;
            String translated;
            try {
                stage2Calls.incrementAndGet();
                stage2Raw = llmProvider.analyzeText(
                        buildStage2SystemPrompt(cfg, triage),
                        triage.source,
                        false);
                translated = stage2Raw == null ? "" : stage2Raw.trim();
            } catch (UnsupportedOperationException uoe) {
                lastError.set("현재 LLM provider(" + properties.getLlm().getProvider()
                        + ")는 텍스트 번역(analyzeText)을 지원하지 않습니다.");
                log.warn(lastError.get());
                recordDiag("stage2-unsupported", lastError.get(),
                        hashHex, dPrev, dStable, stage1Raw, triage, null);
                broadcast();
                return;
            } catch (Exception e) {
                lastError.set("Stage 2 (번역) 실패: " + e.getMessage());
                log.warn("화면 번역: stage 2 실패", e);
                recordDiag("stage2-fail", "Stage 2 LLM 호출 실패: " + e.getMessage(),
                        hashHex, dPrev, dStable, stage1Raw, triage, null);
                broadcast();
                return;
            }
            if (translated.isBlank()) {
                commitStableHash(currentHash, force);
                recordDiag("stage2-blank", "Stage 2 응답이 빈 문자열",
                        hashHex, dPrev, dStable, stage1Raw, triage, stage2Raw);
                return;
            }
            translated = stripCommonPrefix(translated);
            // LLM 이 "원문을 입력해 주세요" 같은 메타 응답을 뱉었으면 TTS/UI 로 흘려보내지 않음.
            if (looksLikeMetaResponse(translated)) {
                log.debug("화면 번역: stage 2 가 메타-응답 반환 - 폐기. raw={}", translated);
                commitStableHash(currentHash, force);
                recordDiag("stage2-meta",
                        "Stage 2 가 '원문 없음' 식 메타-응답 반환 — 폐기. raw=" + preview(translated, 100),
                        hashHex, dPrev, dStable, stage1Raw, triage, stage2Raw);
                return;
            }

            // ----- translated 유사도 dedup (안전망. force 면 우회) -----
            if (!force) {
                double tSim = similarityRatio(
                        normalizeForDedup(translated),
                        normalizeForDedup(lastTranslated.get()));
                if (tSim >= cfg.getTranslationDedupSimilarity()) {
                    log.debug("화면 번역: translated 유사도 {} ≥ {} → state/TTS/history 스킵",
                            tSim, cfg.getTranslationDedupSimilarity());
                    lastSource.set(triage.source);
                    commitStableHash(currentHash, force);
                    recordDiag("translated-dedup",
                            "translated 유사도 " + String.format("%.2f", tSim)
                                    + " ≥ " + cfg.getTranslationDedupSimilarity() + " — state/TTS/history 스킵",
                            hashHex, dPrev, dStable, stage1Raw, triage, stage2Raw);
                    return;
                }
            }

            // ----- 상태 갱신 -----
            lastSource.set(triage.source);
            lastTranslated.set(translated);
            TranslateEntry entry = new TranslateEntry(
                    UUID.randomUUID().toString(),
                    Instant.now().toString(),
                    triage.speaker,
                    triage.source,
                    translated,
                    splitLines(translated),
                    triage.lang.isBlank() ? "auto" : triage.lang,
                    cfg.getTargetLang());
            currentEntry.set(entry);
            pageIndex.set(0);
            lastError.set(null);
            pushRecent(entry, cfg.getRecentBufferSize());
            entriesProduced.incrementAndGet();
            commitStableHash(currentHash, force);
            broadcast();

            historyService.append(new TranslationHistoryService.HistoryEntry(
                    entry.ts(), entry.speaker(), entry.source(), entry.translated(),
                    entry.sourceLang(), entry.targetLang()));

            if (cfg.isTtsEnabled()) {
                String visible = visibleTextOnPageZero(entry, cfg.getLinesPerPage());
                if (!visible.isBlank()) {
                    enqueueTts(visible);
                    ttsEnqueuedCount.incrementAndGet();
                }
            }
            recordDiag("ok",
                    "번역 완료 — speaker='" + (entry.speaker() == null ? "" : entry.speaker())
                            + "', translated='" + preview(entry.translated(), 80) + "'",
                    hashHex, dPrev, dStable, stage1Raw, triage, stage2Raw);
        } finally {
            translating.set(false);
        }
    }

    /** 이 프레임을 처리 완료(non-retryable)로 마킹 — 다음 같은 화면은 same-stable 스킵. force=true (수동) 면 미리 셋됨. */
    private void commitStableHash(long currentHash, boolean force) {
        if (!force) {
            lastStableHash.set(currentHash);
            hasLastStableHash.set(true);
        }
    }

    private void pushRecent(TranslateEntry entry, int cap) {
        int max = Math.max(1, cap);
        synchronized (recentLock) {
            recent.addFirst(entry);
            while (recent.size() > max) recent.pollLast();
        }
    }

    private List<TranslateEntry> snapshotRecent() {
        synchronized (recentLock) {
            return new ArrayList<>(recent);
        }
    }

    static String visibleTextOnPageZero(TranslateEntry entry, int linesPerPage) {
        if (entry == null || entry.lines() == null || entry.lines().isEmpty()) return "";
        int n = Math.min(Math.max(1, linesPerPage), entry.lines().size());
        return String.join(" ", entry.lines().subList(0, n));
    }

    // ────────────── 프롬프트 ──────────────

    private String buildStage1SystemPrompt(BotProperties.ScreenTranslate cfg, boolean force) {
        String sources = String.join(",", cfg.getSourceLangs());
        String dialogueRule = (cfg.isDialogueOnly() && !force)
                ? "캐릭터 대사 전용. UI 메뉴/버튼/상태창/시스템 메시지는 SKIP."
                : "가장 두드러진 텍스트 1블록 처리.";
        // 출력은 짧고 단호하게. 마크다운/펜스/설명 금지 — 잘림 방지.
        return """
                화면 자막 추출기. 입력: 화면 캡처 1장.
                대상 소스 언어=[%s], 대상 언어=%s (대상언어면 SKIP).
                %s
                출력은 다음 둘 중 정확히 하나만 (설명/마크다운/코드펜스/접두사 전부 금지):
                  대상 없음 → SKIP
                  처리 → {"speaker":"...","source":"...","lang":"..."}
                source 는 원문 그대로 (번역 금지). JSON 은 한 줄, 모든 따옴표·괄호 반드시 닫을 것.
                source 가 너무 길면 (200자 초과) 화면에 가장 두드러진 1문장만.
                """.formatted(sources, cfg.getTargetLang(), dialogueRule);
    }

    private String buildStage1UserPrompt() {
        return "이 화면에서 위 규칙대로 응답하라. SKIP 또는 한 줄 JSON 만.";
    }

    private String buildStage2SystemPrompt(BotProperties.ScreenTranslate cfg, ParsedTriage triage) {
        String src = triage.lang.isBlank() ? "원문" : triage.lang;
        return """
                당신은 자막 번역기입니다. 입력은 %s 텍스트 한 단락.
                지시:
                  1) %s 으로 자연스럽고 매끄럽게 번역.
                  2) 캐릭터 화자 톤(존댓말/반말/특이체)을 유지.
                  3) 번역문만 출력. "번역:", 따옴표, 마크다운, 부가 설명 일체 금지.
                """.formatted(src, cfg.getTargetLang());
    }

    // ────────────── 파싱 ──────────────

    private record ParsedTriage(String speaker, String source, String lang) {}

    // SKIP 토큰이 단어 경계로 등장하면 SKIP 처리 (LLM 이 설명 후 SKIP 붙이는 케이스 흡수)
    private static final Pattern SKIP_TOKEN = Pattern.compile("(?i)(^|\\b|\\n)\\s*SKIP\\s*($|\\b|\\n)");
    // 마크다운 코드 펜스 (```json ... ```) 제거용
    private static final Pattern CODE_FENCE = Pattern.compile("```(?:json|JSON)?\\s*([\\s\\S]*?)```");

    private ParsedTriage parseStage1(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        // 마크다운 코드 펜스 안의 JSON 우선 추출
        Matcher fence = CODE_FENCE.matcher(trimmed);
        String jsonCandidate = null;
        if (fence.find()) {
            jsonCandidate = fence.group(1).trim();
        } else {
            // balanced brace 매칭으로 첫 완전한 {...} 추출
            jsonCandidate = extractFirstJsonObject(trimmed);
        }
        // balanced 매칭이 null 인데 응답이 '{' 로 시작하면 잘림 가능성 — 복구 시도용 후보로 채택
        if (jsonCandidate == null) {
            int firstBrace = trimmed.indexOf('{');
            if (firstBrace >= 0) {
                jsonCandidate = trimmed.substring(firstBrace).trim();
            }
        }
        if (jsonCandidate == null) {
            if (SKIP_TOKEN.matcher(trimmed).find()) {
                log.debug("화면 번역 stage 1: SKIP 응답 (raw {}자)", trimmed.length());
            } else {
                log.debug("화면 번역 stage 1: JSON/SKIP 모두 미발견 - SKIP 처리. raw={}", preview(trimmed, 300));
            }
            return null;
        }
        // 1차 파싱
        ParsedTriage parsed = tryParseJson(jsonCandidate);
        if (parsed == null) {
            // 잘림 복구 시도 — 마지막 닫는 따옴표/괄호 보완
            String recovered = recoverTruncatedJson(jsonCandidate);
            if (recovered != null && !recovered.equals(jsonCandidate)) {
                parsed = tryParseJson(recovered);
                if (parsed != null) {
                    log.debug("화면 번역 stage 1: 잘림 JSON 복구 성공. raw={}", preview(trimmed, 200));
                }
            }
        }
        if (parsed == null) {
            log.debug("화면 번역 stage 1: JSON 파싱 실패. raw={}", preview(trimmed, 300));
            return null;
        }
        if (!isValidSource(parsed.source, parsed.lang)) {
            log.debug("화면 번역 stage 1: source 검증 실패 ('{}', lang='{}') - SKIP 처리", parsed.source, parsed.lang);
            return null;
        }
        return parsed;
    }

    private ParsedTriage tryParseJson(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String speaker = textOrEmpty(root, "speaker");
            String source = textOrEmpty(root, "source");
            String lang = textOrEmpty(root, "lang");
            return new ParsedTriage(speaker, source, lang);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 첫 균형 잡힌 {...} 객체 추출. 문자열 안의 중괄호/이스케이프 처리.
     * 균형이 안 맞으면 (잘림 등) null 반환.
     */
    static String extractFirstJsonObject(String s) {
        int start = s.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) { escape = false; continue; }
            if (inString) {
                if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return s.substring(start, i + 1);
            }
        }
        return null;
    }

    /**
     * 잘린 JSON 복구. 흔한 케이스 한정:
     *   {"speaker":"x","source":"긴 텍스트 중간에 끊김
     * → 마지막 미닫은 따옴표 닫고, 미닫은 중괄호도 닫아서 파싱 가능하게.
     * 복구 불가/이미 정상이면 원본 그대로 반환.
     */
    static String recoverTruncatedJson(String s) {
        if (s == null || s.isEmpty()) return s;
        int idx = s.lastIndexOf('{');
        if (idx < 0) return s;
        // 따옴표 짝수 개인지 확인 — 홀수면 닫는 " 빠짐
        int quoteCount = 0;
        boolean escape = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\') { escape = true; continue; }
            if (c == '"') quoteCount++;
        }
        StringBuilder sb = new StringBuilder(s);
        // 끝에 trailing 콤마가 있으면 제거 (LLM 이 마지막 키 미완성한 경우)
        while (sb.length() > 0) {
            char last = sb.charAt(sb.length() - 1);
            if (last == ' ' || last == '\n' || last == '\r' || last == '\t' || last == ',') {
                sb.deleteCharAt(sb.length() - 1);
            } else break;
        }
        if (quoteCount % 2 != 0) sb.append('"');
        // 균형 안 맞으면 } 보충
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (esc) { esc = false; continue; }
            if (inStr) {
                if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        for (int i = 0; i < depth; i++) sb.append('}');
        return sb.toString();
    }

    /**
     * stage 1 이 뽑은 source 가 실제 번역할 가치가 있는지 검증.
     * - 너무 짧음(2자 미만)
     * - lang 코드 그 자체("ja", "en" 같은 2~3자 ASCII)
     * - 공백/구두점만
     * 위 경우 SKIP 처리해서 stage 2 가 "원문 없음" 식 메타 응답 뱉는 것 차단.
     */
    private static boolean isValidSource(String source, String lang) {
        if (source == null) return false;
        String t = source.trim();
        if (t.length() < 2) return false;
        // lang 코드 그대로면 (예: source="ja") 무효
        if (lang != null && !lang.isBlank() && t.equalsIgnoreCase(lang.trim())) return false;
        // 알파벳 2~3자만이면 lang 코드일 가능성 — 무효
        if (t.length() <= 3 && t.matches("[A-Za-z]+")) return false;
        // 공백/구두점만이면 무효
        if (t.replaceAll("[\\s\\p{Punct}]", "").isEmpty()) return false;
        return true;
    }

    // LLM 이 빈 입력에 대해 정중하게 "원문 없습니다" 라고 반응할 때의 메타-응답 시그니처.
    // 이런 텍스트가 TTS 로 흘러가지 않도록 stage 2 응답 검증에 사용.
    private static final Pattern META_RESPONSE_PATTERN = Pattern.compile(
            "(원문을\\s*입력|텍스트가?\\s*(없|입력)|번역(할|할 수 있는)?\\s*(원문|텍스트|내용)|"
          + "原文(を|が)|入力して|翻訳(すべき|する)\\s*(原文|テキスト|内容)|"
          + "no\\s*(text|source|input)|please\\s*provide|empty\\s*(text|input))",
            Pattern.CASE_INSENSITIVE);

    private static boolean looksLikeMetaResponse(String s) {
        if (s == null || s.isBlank()) return false;
        return META_RESPONSE_PATTERN.matcher(s).find();
    }

    private static final Pattern LEADING_LABEL =
            Pattern.compile("^\\s*(번역|translation|translated)\\s*[:：]\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern WRAPPING_QUOTES = Pattern.compile("^[\"'「『](.*)[\"'」』]$", Pattern.DOTALL);

    private static String stripCommonPrefix(String s) {
        if (s == null) return "";
        String out = LEADING_LABEL.matcher(s).replaceFirst("").trim();
        Matcher mq = WRAPPING_QUOTES.matcher(out);
        if (mq.matches()) out = mq.group(1).trim();
        return out;
    }

    private static String textOrEmpty(JsonNode node, String field) {
        if (node == null) return "";
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return "";
        return v.asText("").trim();
    }

    // ────────────── dedup helpers ──────────────

    /** 비교 전 정규화 — 공백/문장부호/따옴표 제거 + lowercase. */
    static String normalizeForDedup(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s\\p{Punct}「」『』\"'.…]+", "").toLowerCase();
    }

    /** Levenshtein 기반 유사도 (0~1). 둘 다 빈 문자열이면 1. */
    static double similarityRatio(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        int max = Math.max(a.length(), b.length());
        if (max == 0) return 1.0;
        int d = levenshtein(a, b);
        return 1.0 - (double) d / max;
    }

    private static int levenshtein(String a, String b) {
        int m = a.length(), n = b.length();
        if (m == 0) return n;
        if (n == 0) return m;
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(
                        Math.min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[n];
    }

    // ────────────── line split ──────────────

    static List<String> splitLines(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String paragraph : text.split("\\R")) {
            paragraph = paragraph.trim();
            if (paragraph.isEmpty()) continue;
            String[] sentences = paragraph.split("(?<=[.!?。！？])\\s+");
            for (String s : sentences) {
                s = s.trim();
                if (s.isEmpty()) continue;
                while (s.length() > 40) {
                    int cut = preferSpaceCut(s, 40);
                    out.add(s.substring(0, cut).trim());
                    s = s.substring(cut).trim();
                }
                if (!s.isEmpty()) out.add(s);
            }
        }
        return out;
    }

    private static int preferSpaceCut(String s, int maxLen) {
        int idx = s.lastIndexOf(' ', maxLen);
        return (idx >= 20) ? idx : maxLen;
    }

    // ────────────── SSE / TTS ──────────────

    private void broadcast() {
        TranslateState st = currentState();
        for (SseEmitter em : subscribers) {
            try {
                em.send(SseEmitter.event().name("state").data(st));
            } catch (Throwable t) {
                // 클라이언트 끊긴 emitter — 제거 + completeWithError 로 Tomcat async 머신에 종료 통보.
                // 이걸 안 하면 Tomcat 이 "Servlet.service() threw exception" 으로 2중 로그함.
                log.debug("SSE emitter 제거: {}", t.getMessage());
                subscribers.remove(em);
                try { em.completeWithError(t); } catch (Throwable ignored) {}
            }
        }
    }

    private void enqueueTts(String text) {
        pendingTts.set(text);
        ttsSignal.release();
    }

    private void ttsLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                ttsSignal.acquire();
                String text = pendingTts.getAndSet(null);
                if (text != null && !text.isBlank()) {
                    // 아바타(/avatar) 입 동기화 — TTS 시작/종료에 SSE 이벤트 푸시.
                    // ReactionOrchestrator 가 발화할 때와 동일한 통로 → 같은 아바타가 그대로 반응.
                    boolean started = false;
                    try {
                        avatarEvents.speakStart();
                        started = true;
                        ttsService.speak(text);
                    } catch (Exception e) {
                        log.debug("TTS 실패(무시): {}", e.getMessage());
                    } finally {
                        if (started) {
                            try { avatarEvents.speakEnd(); } catch (Exception ignored) {}
                        }
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("TTS loop 에러: {}", e.getMessage());
            }
        }
    }
}
