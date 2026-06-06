package com.jhkim.reactionbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhkim.reactionbot.config.BotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 포켓몬 오버레이 핵심 로직.
 *
 * 흐름:
 *   1) ScreenCaptureService 로 스크린샷 받음
 *   2) LlmProvider.analyzeImage 로 raw vision JSON 응답 요청 (페르소나/히스토리 미오염)
 *   3) JSON 파싱 → 식별자(영문 슬러그 우선, 없으면 일어) 추출
 *   4) 직전 결과와 동일하면 PokéAPI 재호출 생략 (요청 절감)
 *   5) PokemonSpeciesService 로 세대 기준 종족값/타입/스프라이트 조회
 *   6) 종족값 스피드 내림차순 정렬, 같은 종 두 마리면 mirror=true
 *   7) 메모리 상태 갱신 → /api/pokemon-overlay/state 가 폴링
 *
 * 동시성: analyze()는 in-flight 동안 중복 호출 무시 (스케줄러와 수동 버튼이 겹쳐도 안전).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PokemonOverlayService {

    private final BotProperties properties;
    private final LlmProvider llmProvider;
    private final ScreenCaptureService screenCapture;
    private final PokemonSpeciesService speciesService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicReference<OverlayState> state = new AtomicReference<>(OverlayState.empty());
    private final java.util.concurrent.atomic.AtomicBoolean analyzing = new java.util.concurrent.atomic.AtomicBoolean(false);

    public record OverlayState(
            boolean enabled,
            int generation,
            String mode,
            int maxPokemon,
            boolean mirror,
            String lastError,
            Instant updatedAt,
            List<Card> cards
    ) {
        public static OverlayState empty() {
            return new OverlayState(false, 9, "manual", 2, false, null, null, List.of());
        }
    }

    public record Card(
            String slug,
            String nameKo,
            String nameJa,
            int generation,
            List<String> types,
            int hp, int atk, int def, int spa, int spd, int spe,
            int statTotal,
            int speedRank,            // 1=가장 빠름
            String spriteUrl,
            String artworkUrl,
            List<String> inferredMoves // 비어있을 수 있음
    ) {}

    /**
     * 현재 상태 스냅샷. 컨트롤러 → 폴링 응답.
     * 항상 properties 의 enabled/generation/mode/maxPokemon을 반영한 최신 메타로 갱신해서 반환.
     */
    public OverlayState currentState() {
        OverlayState cur = state.get();
        BotProperties.Overlay cfg = properties.getPokemon().getOverlay();
        return new OverlayState(
                cfg.isEnabled(),
                cfg.getGeneration(),
                cfg.getMode(),
                cfg.getMaxPokemon(),
                cur.mirror(),
                cur.lastError(),
                cur.updatedAt(),
                cur.cards()
        );
    }

    /**
     * 스크린샷 캡처 → LLM 분석 → 상태 갱신.
     * - enabled=false 면 no-op
     * - 이미 진행 중이면 skip (in-flight 잠금)
     * - 화면 단색이면 카드 비움
     *
     * @param force true면 직전 결과와 같아도 PokéAPI 재조회 (수동 새로고침)
     */
    public OverlayState analyze(boolean force) {
        BotProperties.Overlay cfg = properties.getPokemon().getOverlay();
        if (!properties.getPokemon().isEnabled() || !cfg.isEnabled()) {
            return currentState();
        }
        if (!analyzing.compareAndSet(false, true)) {
            log.debug("분석 진행 중 - 중복 호출 무시");
            return currentState();
        }
        try {
            ScreenCaptureService.Capture cap = screenCapture.captureBase64Jpeg();
            if (cap.blank() || cap.base64Jpeg() == null) {
                log.info("오버레이 분석: 단색 화면 - 카드 비움");
                updateCards(List.of(), false, null);
                return currentState();
            }

            String raw = callLlm(cap.base64Jpeg(), cfg);
            log.debug("오버레이 LLM raw 응답: {}", raw);

            List<Detected> detected = parseDetections(raw, cfg.getMaxPokemon());
            if (detected.isEmpty()) {
                log.info("오버레이 분석: 포켓몬 미검출");
                updateCards(List.of(), false, null);
                return currentState();
            }

            if (!force && sameAsPrevious(detected)) {
                log.debug("오버레이: 직전과 동일 - PokéAPI 재조회 생략");
                return currentState();
            }

            List<Card> cards = buildCards(detected, cfg.getGeneration(), cfg.isInferMoves());
            boolean mirror = isMirror(cards);
            updateCards(cards, mirror, null);
            log.info("오버레이 갱신: {}종 (mirror={})", cards.size(), mirror);
        } catch (UnsupportedOperationException uoe) {
            log.warn("현재 LLM provider는 raw vision 미지원: {}", uoe.getMessage());
            setError("현재 LLM provider는 포켓몬 오버레이 미지원");
        } catch (Exception e) {
            log.warn("오버레이 분석 실패: {}", e.getMessage());
            setError(e.getMessage() == null ? "분석 실패" : e.getMessage());
        } finally {
            analyzing.set(false);
        }
        return currentState();
    }

    // ---------- LLM 호출 ----------

    private static final String SYSTEM_PROMPT = """
            당신은 포켓몬 게임 화면을 분석하는 비전 분석기다. 인사·해설·캐릭터 연기 금지.
            반드시 JSON 객체만 출력. 마크다운 코드펜스 금지. 다른 텍스트 일절 금지.

            스키마:
            {"pokemons":[{"slug":"<영문 PokéAPI 슬러그, 예: garchomp>","name_ja":"<일어 카타카나 이름>"}]}

            규칙:
            - 화면에 보이는 포켓몬을 위에서 아래 또는 왼쪽에서 오른쪽 순서로 배열. 최대 N마리.
            - "닉네임/별칭" 표시는 무시하고 포켓몬 외형(도트/모델)으로 종을 판단.
            - 같은 종이 두 마리면 똑같이 두 번 포함 (중복 OK).
            - 슬러그는 소문자, 영문, '-' 만. 메가/지역폼은 "charizard-mega-x", "vulpix-alola" 형식.
            - 종을 확실히 모르면 그 포켓몬은 생략 (틀리느니 빠뜨려라).
            - 화면에 포켓몬 전투/메뉴/대전 UI가 보이지 않으면 빈 배열 반환.
            """;

    private String callLlm(String base64Image, BotProperties.Overlay cfg) {
        String sys = SYSTEM_PROMPT.replace("N마리", cfg.getMaxPokemon() + "마리");
        String userPrompt = "이 화면에 보이는 포켓몬을 위 스키마대로 JSON으로만 응답하라. "
                + "현재 표시 모드: " + (cfg.getMaxPokemon() == 4 ? "더블배틀(최대 4)" : "싱글배틀(최대 2)") + ".";
        return llmProvider.analyzeImage(sys, userPrompt, base64Image);
    }

    // ---------- 파싱 ----------

    private record Detected(String slug, String nameJa) {}

    /** LLM 응답에서 JSON 부분만 추출 → pokemons 배열 파싱. 코드펜스/잡설 섞여도 견고하게 동작. */
    private List<Detected> parseDetections(String raw, int max) {
        if (raw == null || raw.isBlank()) return List.of();
        String json = extractJsonObject(raw);
        if (json == null) {
            log.debug("LLM 응답에서 JSON 객체 미발견");
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode arr = root.path("pokemons");
            if (!arr.isArray()) return List.of();
            List<Detected> out = new ArrayList<>();
            for (JsonNode n : arr) {
                if (out.size() >= max) break;
                String slug = n.path("slug").asText("").trim().toLowerCase();
                String ja = n.path("name_ja").asText("").trim();
                if (slug.isEmpty() && ja.isEmpty()) continue;
                out.add(new Detected(slug, ja));
            }
            return out;
        } catch (Exception e) {
            log.debug("LLM JSON 파싱 실패: {}", e.getMessage());
            return List.of();
        }
    }

    /** 첫 '{' 부터 매칭되는 '}'까지 잘라 반환. 코드펜스 안에 있어도 동작. */
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*\\}");

    private String extractJsonObject(String s) {
        Matcher m = JSON_OBJECT.matcher(s);
        if (!m.find()) return null;
        String candidate = m.group();
        // 중괄호 균형 잡기 (LLM이 trailing 텍스트를 붙였을 때 대비)
        int depth = 0;
        int end = -1;
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) { end = i + 1; break; }
            }
        }
        if (end < 0) return null;
        return candidate.substring(0, end);
    }

    // ---------- 변화 감지 ----------

    /**
     * 직전 카드 슬러그 집합과 비교. 정렬·중복 보존을 위해 멀티셋(리스트 정렬) 비교.
     * 같으면 PokéAPI 재호출 생략.
     */
    private boolean sameAsPrevious(List<Detected> next) {
        List<Card> prev = state.get().cards();
        if (prev.size() != next.size()) return false;
        List<String> a = prev.stream().map(Card::slug).sorted().toList();
        List<String> b = next.stream().map(Detected::slug).sorted().toList();
        return a.equals(b) && !a.isEmpty();
    }

    // ---------- 카드 구성 ----------

    private List<Card> buildCards(List<Detected> detected, int generation, boolean inferMoves) {
        List<Card> raw = new ArrayList<>();
        for (Detected d : detected) {
            String key = !d.slug().isEmpty() ? d.slug() : d.nameJa();
            PokemonSpeciesService.SpeciesInfo info = speciesService.lookup(key, generation);
            if (info == null) {
                log.debug("species 미해결: slug='{}', ja='{}'", d.slug(), d.nameJa());
                continue;
            }
            raw.add(new Card(
                    info.slug(),
                    info.nameKo(),
                    info.nameJa(),
                    info.generation(),
                    info.types(),
                    info.baseStats().hp(),
                    info.baseStats().atk(),
                    info.baseStats().def(),
                    info.baseStats().spa(),
                    info.baseStats().spd(),
                    info.baseStats().spe(),
                    info.baseStats().total(),
                    0,
                    info.spriteUrl(),
                    info.artworkUrl(),
                    inferMoves ? List.of() : List.of() // TODO: inferMoves=true시 별도 LLM 호출
            ));
        }
        raw.sort(Comparator.comparingInt(Card::spe).reversed());
        List<Card> ranked = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            Card c = raw.get(i);
            ranked.add(new Card(
                    c.slug(), c.nameKo(), c.nameJa(), c.generation(), c.types(),
                    c.hp(), c.atk(), c.def(), c.spa(), c.spd(), c.spe(), c.statTotal(),
                    i + 1, c.spriteUrl(), c.artworkUrl(), c.inferredMoves()
            ));
        }
        return ranked;
    }

    private boolean isMirror(List<Card> cards) {
        if (cards.size() < 2) return false;
        Set<String> unique = new LinkedHashSet<>();
        for (Card c : cards) unique.add(c.slug());
        return unique.size() < cards.size();
    }

    // ---------- 상태 갱신 ----------

    private void updateCards(List<Card> cards, boolean mirror, String error) {
        BotProperties.Overlay cfg = properties.getPokemon().getOverlay();
        state.set(new OverlayState(
                cfg.isEnabled(),
                cfg.getGeneration(),
                cfg.getMode(),
                cfg.getMaxPokemon(),
                mirror,
                error,
                Instant.now(),
                cards
        ));
    }

    private void setError(String msg) {
        OverlayState cur = state.get();
        BotProperties.Overlay cfg = properties.getPokemon().getOverlay();
        state.set(new OverlayState(
                cfg.isEnabled(),
                cfg.getGeneration(),
                cfg.getMode(),
                cfg.getMaxPokemon(),
                cur.mirror(),
                msg,
                Instant.now(),
                cur.cards()
        ));
    }
}
