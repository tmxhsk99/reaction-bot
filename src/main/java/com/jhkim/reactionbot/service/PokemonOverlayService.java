package com.jhkim.reactionbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhkim.reactionbot.config.BotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
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
            List<PokemonSpeciesService.Weakness> weaknesses, // 세대 적용 약점 (>=2배)
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
        // 비활성 케이스를 사일런트로 두면 사용자가 "버튼 눌렀는데 아무 일도 안 일어남"으로 인지.
        // 분명한 INFO 로그 + lastError 셋업으로 UI에 안내.
        if (!properties.getPokemon().isEnabled()) {
            log.info("오버레이 analyze 호출됨 — pokemon.enabled=false 라 비활성. no-op.");
            setError("pokemon.enabled=false (reaction-bot.pokemon.enabled=true 로 켜고 서버 재기동 필요)");
            return currentState();
        }
        if (!cfg.isEnabled()) {
            log.info("오버레이 analyze 호출됨 — pokemon.overlay.enabled=false 라 비활성. no-op.");
            setError("오버레이가 꺼져 있음 (/config 에서 '포켓몬 오버레이' 체크 후 저장 + 서버 재기동)");
            return currentState();
        }
        if (!analyzing.compareAndSet(false, true)) {
            log.info("오버레이 analyze 호출됨 — 직전 분석 진행 중이라 무시.");
            return currentState();
        }
        try {
            log.info("오버레이 분석 시작 (mode={}, gen={}, maxPokemon={}, force={}, provider={})",
                    cfg.getMode(), cfg.getGeneration(), cfg.getMaxPokemon(), force,
                    properties.getLlm().getProvider());

            ScreenCaptureService.Capture cap;
            try {
                cap = screenCapture.captureBase64Jpeg();
            } catch (Exception e) {
                log.warn("오버레이: 화면 캡처 실패", e);
                setError("화면 캡처 실패: " + e.getMessage());
                return currentState();
            }
            if (cap.blank() || cap.base64Jpeg() == null) {
                log.info("오버레이 분석: 단색 화면 - 카드 비움");
                updateCards(List.of(), false, null);
                return currentState();
            }
            log.info("오버레이: 캡처 완료 ({}자 base64). LLM 호출…", cap.base64Jpeg().length());

            // crop + ignoreRegion 적용. 봇 자체 오버레이가 화면에 떠있어도 마스킹해서 LLM이 무시.
            String preparedImage;
            try {
                preparedImage = prepareImage(cap.base64Jpeg(), cfg);
            } catch (Exception e) {
                log.warn("이미지 전처리 실패. 원본으로 진행: {}", e.getMessage());
                preparedImage = cap.base64Jpeg();
            }

            String raw;
            try {
                raw = callLlm(preparedImage, cfg);
            } catch (UnsupportedOperationException uoe) {
                throw uoe;
            } catch (Exception e) {
                log.warn("오버레이: LLM 호출 실패", e);
                setError("LLM 호출 실패: " + e.getMessage());
                return currentState();
            }
            log.info("오버레이 LLM 응답 수신 ({}자)", raw == null ? 0 : raw.length());
            log.debug("오버레이 LLM raw: {}", raw);

            List<Detected> detected = parseDetections(raw, cfg.getMaxPokemon());
            log.info("오버레이 LLM 파싱: {}종 추출", detected.size());
            if (detected.isEmpty()) {
                log.info("오버레이 분석: 포켓몬 미검출");
                updateCards(List.of(), false, null);
                return currentState();
            }

            if (!force && sameAsPrevious(detected)) {
                log.info("오버레이: 직전과 동일 슬러그 — PokéAPI 재조회 생략");
                return currentState();
            }

            List<Card> cards = buildCards(detected, cfg.getGeneration(), cfg.isInferMoves());
            boolean mirror = isMirror(cards);
            updateCards(cards, mirror, null);
            log.info("오버레이 갱신 완료: {}종 (mirror={})", cards.size(), mirror);
        } catch (UnsupportedOperationException uoe) {
            log.warn("현재 LLM provider는 raw vision 미지원: {}", uoe.getMessage());
            setError("현재 LLM provider(" + properties.getLlm().getProvider() + ")는 raw vision 미지원");
        } catch (Exception e) {
            log.warn("오버레이 분석 실패", e);
            setError(e.getMessage() == null ? "분석 실패" : e.getMessage());
        } finally {
            analyzing.set(false);
        }
        return currentState();
    }

    // ---------- 수동 입력 (LLM 인식 실패 시 fallback) ----------

    /**
     * 사용자가 입력한 이름들로 카드 셋업. 일어/한글/영문 어느 입력이든 PokemonSpeciesService.resolveSlug 가 처리.
     * enabled 체크는 우회 (사용자가 명시적으로 누른 액션). 단, pokemon.enabled=false 면 species 조회는 동작.
     * maxPokemon 초과분은 컷.
     */
    public OverlayState applyManual(List<String> names) {
        BotProperties.Overlay cfg = properties.getPokemon().getOverlay();
        if (names == null || names.isEmpty()) {
            log.info("수동 입력: 빈 목록 — 카드 비움");
            updateCards(List.of(), false, null);
            return currentState();
        }
        log.info("수동 입력 적용 시작: {} (gen={}, max={})", names, cfg.getGeneration(), cfg.getMaxPokemon());
        List<Detected> det = new ArrayList<>();
        for (String n : names) {
            if (det.size() >= cfg.getMaxPokemon()) break;
            if (n == null) continue;
            String trimmed = n.trim();
            if (trimmed.isEmpty()) continue;
            // 일어/한글/영문 모두 nameJa 슬롯에 넣어 buildCards 의 일어 우선 lookup 통과시킴.
            det.add(new Detected("", trimmed));
        }
        if (det.isEmpty()) {
            updateCards(List.of(), false, null);
            return currentState();
        }
        try {
            List<Card> cards = buildCards(det, cfg.getGeneration(), cfg.isInferMoves());
            if (cards.isEmpty()) {
                log.info("수동 입력: 모든 이름 lookup 실패 (가능 원인: 일어 인덱스 미빌드 + 한글/일어 입력)");
                setError("입력 이름 매칭 실패. 영문 슬러그(예: garchomp) 또는 정확한 한글명 시도 / 인덱스 빌드 대기.");
                return currentState();
            }
            boolean mirror = isMirror(cards);
            updateCards(cards, mirror, null);
            log.info("수동 입력 적용 완료: {}종 (mirror={})", cards.size(), mirror);
        } catch (Exception e) {
            log.warn("수동 입력 처리 실패", e);
            setError("수동 입력 실패: " + e.getMessage());
        }
        return currentState();
    }

    /** 카드 비우기 (수동 초기화). */
    public OverlayState clearCards() {
        log.info("오버레이 카드 비움 (수동)");
        updateCards(List.of(), false, null);
        return currentState();
    }

    // ---------- 이미지 전처리 (crop + ignoreRegion 마스킹) ----------

    /**
     * cropRegion 적용 후 ignoreRegion(들)을 검정 박스로 마스킹한 base64 JPEG 반환.
     * 둘 다 빈 값이면 원본 그대로 (디코드/재인코드 비용 회피).
     * 좌표는 0~1 정규화. cropRegion 적용 후 ignoreRegion 좌표는 *남은 이미지* 기준.
     */
    private String prepareImage(String base64Jpeg, BotProperties.Overlay cfg) throws Exception {
        boolean hasCrop = cfg.getCropRegion() != null && !cfg.getCropRegion().isBlank();
        boolean hasIgnore = cfg.getIgnoreRegion() != null && !cfg.getIgnoreRegion().isBlank();
        if (!hasCrop && !hasIgnore) return base64Jpeg;

        byte[] bytes = Base64.getDecoder().decode(base64Jpeg);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        if (img == null) return base64Jpeg;

        if (hasCrop) {
            double[] r = parseRegion(cfg.getCropRegion());
            if (r != null) {
                int x = clamp((int) (r[0] * img.getWidth()), 0, img.getWidth() - 1);
                int y = clamp((int) (r[1] * img.getHeight()), 0, img.getHeight() - 1);
                int w = clamp((int) (r[2] * img.getWidth()), 1, img.getWidth() - x);
                int h = clamp((int) (r[3] * img.getHeight()), 1, img.getHeight() - y);
                BufferedImage sub = img.getSubimage(x, y, w, h);
                // getSubimage는 원본 공유 view → 독립 사본 만들어 Graphics 변경 가능하게.
                BufferedImage copy = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = copy.createGraphics();
                g.drawImage(sub, 0, 0, null);
                g.dispose();
                img = copy;
                log.debug("오버레이: cropRegion 적용 ({}x{})", w, h);
            }
        }

        if (hasIgnore) {
            // copy on write: ignore 적용 시점에 항상 새 이미지로 (원본 그대로 들어왔을 경우 대비)
            BufferedImage copy = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = copy.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.setColor(Color.BLACK);
            for (String part : cfg.getIgnoreRegion().split(";")) {
                double[] r = parseRegion(part);
                if (r == null) continue;
                int x = clamp((int) (r[0] * img.getWidth()), 0, img.getWidth());
                int y = clamp((int) (r[1] * img.getHeight()), 0, img.getHeight());
                int w = clamp((int) (r[2] * img.getWidth()), 0, img.getWidth() - x);
                int h = clamp((int) (r[3] * img.getHeight()), 0, img.getHeight() - y);
                g.fillRect(x, y, w, h);
                log.debug("오버레이: ignoreRegion 마스킹 ({},{},{}x{})", x, y, w, h);
            }
            g.dispose();
            img = copy;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    /** "x,y,w,h" → double[4]. 파싱 실패 시 null. */
    private double[] parseRegion(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        try {
            String[] parts = t.split(",");
            if (parts.length != 4) return null;
            double[] r = new double[4];
            for (int i = 0; i < 4; i++) {
                r[i] = Double.parseDouble(parts[i].trim());
                if (r[i] < 0) r[i] = 0;
                if (r[i] > 1) r[i] = 1;
            }
            return r;
        } catch (Exception e) {
            log.debug("region 파싱 실패: '{}'", s);
            return null;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---------- LLM 호출 ----------

    private static final String SYSTEM_PROMPT = """
            당신은 포켓몬 게임 화면을 분석하는 비전 분석기다. 인사·해설·캐릭터 연기 금지.
            반드시 JSON 객체만 출력. 마크다운 코드펜스 금지. 다른 텍스트 일절 금지.

            스키마:
            {"pokemons":[{"name_ja":"<일어 카타카나 이름 (필수)>","slug":"<영문 PokéAPI 슬러그 (보조)>"}]}

            규칙 — 일어 이름을 1순위로 정확히 뽑아라:
            - name_ja: 카타카나 표기. 게임 화면이 일본판이라 이름 라벨이 보이면 그걸 정확히 읽기.
              화면에 일어 이름이 안 보여도 포켓몬 외형으로 식별한 종의 공식 일어명을 카타카나로 적어라.
              예: ガブリアス, ホウオウ, カビゴン, リザードン, ピカチュウ, サーナイト
            - slug: 잘 알면 적고, 아니면 빈 문자열로 둬도 된다. 일어가 우선이라 slug 모르면 강제로 추측 금지.
              형식은 소문자/영문/'-'. 메가·지역폼은 "charizard-mega-x", "vulpix-alola" 형식.
            - 화면의 "닉네임/별칭" 표시는 무시하고 외형(도트/모델)으로 종을 판단.
            - 같은 종이 두 마리면 똑같이 두 번 포함 (중복 OK).
            - 최대 N마리.
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
            // 일어 카타카나가 게임 표기·외형 모두에서 더 안정적 → 1순위.
            // 일어 인덱스가 아직 안 빌드되어 있거나 인덱스에 없으면 영문 slug로 폴백.
            PokemonSpeciesService.SpeciesInfo info = null;
            if (!d.nameJa().isEmpty()) {
                info = speciesService.lookup(d.nameJa(), generation);
                if (info == null) {
                    log.debug("species 미해결(ja): '{}'. slug 폴백 시도.", d.nameJa());
                }
            }
            if (info == null && !d.slug().isEmpty()) {
                info = speciesService.lookup(d.slug(), generation);
            }
            if (info == null) {
                log.debug("species 미해결: ja='{}', slug='{}'", d.nameJa(), d.slug());
                continue;
            }
            List<PokemonSpeciesService.Weakness> weaks = speciesService.computeWeaknesses(info.types(), generation);
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
                    weaks,
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
                    i + 1, c.spriteUrl(), c.artworkUrl(), c.weaknesses(), c.inferredMoves()
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
