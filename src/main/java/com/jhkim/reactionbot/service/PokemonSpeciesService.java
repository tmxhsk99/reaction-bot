package com.jhkim.reactionbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhkim.reactionbot.config.BotProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PokéAPI에서 한 종(species)의 다국어 이름·세대 데이터·종족값·스프라이트를 받아 캐싱.
 *
 * 오버레이 전용. PokemonContextService와 캐시 분리 — 응답 모양(JSON DTO)이 다르고
 * 세대 필터·past values 처리가 추가됨.
 *
 * 키 정책:
 *   - LLM이 인식한 영문 슬러그(예: "garchomp") 또는 일어 가타카나/히라가나(예: "ガブリアス")를 받는다.
 *   - 영문이 들어오면 바로 species/pokemon 호출. 일어만 있으면 dex 인덱스를 lazy 빌드해 영문 슬러그로 변환.
 *
 * 세대 처리:
 *   - 응답의 past_types / past_abilities는 세대 기준으로 필터해 그 세대 시점 데이터로 덮어쓴다.
 *   - 종족값(stats) past_values 가 PokéAPI에 안 들어있는 경우가 있어 stats는 현재값을 그대로 사용.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PokemonSpeciesService {

    private final BotProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    // 일어(가타카나/히라가나) → 영문 슬러그 인덱스. 초기엔 빈 맵(=lookup 호출은 즉시 미스 반환, 영문 폴백 유도).
    // 디스크 캐시 로드 / 백그라운드 빌드가 완료되면 volatile 교체.
    // null 이 아닌 빈 맵으로 초기화한 이유: 워밍업 중 lookup 이 synchronized buildJaIndex 를 호출해 호출 스레드가 차단되는 사고를 끊기 위함.
    private volatile Map<String, String> jaToSlug = Map.of();

    /**
     * 세대별 종족값 override 매핑. classpath:pokemon-past-stats.json 에서 로드.
     * PokéAPI 가 past_values 를 안 줘서 수동 관리. 빈 매핑이어도 안전(현재값 사용).
     */
    private record PastStats(int untilGen, Map<String, Integer> stats) {}
    private volatile Map<String, List<PastStats>> pastStatsBySlug = Map.of();

    @Value("${user.dir:.}")
    private String workingDir;

    /**
     * 인덱스 파일 schema. 구조 변경 시 +1 하면 옛 파일은 무효화되어 재빌드됨.
     * v1: {schema, builtAt, pokeapiBase, size, entries: {name → slug}}
     */
    private static final int INDEX_SCHEMA = 1;
    private static final String INDEX_FILE_NAME = "pokemon-name-index.json";

    /** 디스크 캐시 파일 위치. ./data/ 하위. */
    private Path indexFile() {
        return Paths.get(workingDir, "data", INDEX_FILE_NAME);
    }

    /**
     * overlay 활성 시 startup 시점에 인덱스 준비.
     *  1) 디스크 캐시 있으면 그대로 로드 → PokéAPI 호출 0회 (대부분의 재기동 경로)
     *  2) 없으면 백그라운드 빌드 후 디스크에 저장 → 다음 기동부턴 1)로
     * - overlay.enabled=false 면 아예 시작 안 함
     * - 가상 스레드라 메인 startup blocking 없음
     * - 빌드 도중 사용자가 분석 호출하면 영문 slug 폴백으로 자연스럽게 동작
     */
    @PostConstruct
    public void init() {
        // 세대별 종족값 매핑은 overlay 비활성이어도 로드 (작은 비용). 활성화 시점에 즉시 활용 가능.
        loadPastStatsMappings();
        if (!properties.getPokemon().isEnabled() || !properties.getPokemon().getOverlay().isEnabled()) {
            log.info("PokemonSpeciesService: overlay 비활성 — 일어 인덱스 워밍업 생략.");
            return;
        }
        Map<String, String> fromDisk = loadIndexFromDisk();
        if (fromDisk != null && !fromDisk.isEmpty()) {
            jaToSlug = fromDisk;
            log.info("PokemonSpeciesService: 디스크 인덱스 로드 완료 ({}개). PokéAPI 호출 생략.", fromDisk.size());
            return;
        }
        log.info("PokemonSpeciesService: 디스크 인덱스 없음 — 백그라운드 빌드 시작 (이후 ./data/{} 에 저장).", INDEX_FILE_NAME);
        Thread.startVirtualThread(this::rebuildAndSave);
    }

    /**
     * 강제 재빌드. 디스크 파일 무시하고 PokéAPI를 다시 모두 호출 → 새 인덱스로 덮어씀.
     * /api/pokemon-overlay/rebuild-index 가 호출. 새 세대 출시 등으로 데이터 갱신이 필요할 때.
     * 비동기 실행 — 빌드 중에도 옛 인덱스로 자동완성/lookup 계속 동작.
     */
    public void triggerRebuild() {
        log.info("PokemonSpeciesService: 인덱스 강제 재빌드 요청 — 백그라운드 시작.");
        Thread.startVirtualThread(this::rebuildAndSave);
    }

    private void rebuildAndSave() {
        try {
            Map<String, String> idx = buildJaIndex();
            jaToSlug = idx;
            saveIndexToDisk(idx);
        } catch (Exception e) {
            log.warn("일어 인덱스 빌드/저장 실패: {}", e.getMessage());
        }
    }

    /** 디스크 파일 → ConcurrentHashMap. schema 불일치 / pokeapiBase 변경 시 null 반환(=재빌드 유도). */
    private Map<String, String> loadIndexFromDisk() {
        Path file = indexFile();
        if (!Files.exists(file)) return null;
        try {
            JsonNode root = objectMapper.readTree(file.toFile());
            int schema = root.path("schema").asInt(0);
            if (schema != INDEX_SCHEMA) {
                log.info("인덱스 schema 불일치 (file={}, 현재={}). 재빌드 예정.", schema, INDEX_SCHEMA);
                return null;
            }
            String storedBase = root.path("pokeapiBase").asText("");
            String currentBase = properties.getPokemon().getPokeapiBase();
            if (currentBase != null && !storedBase.isEmpty() && !storedBase.equals(currentBase)) {
                log.info("인덱스 pokeapiBase 변경 감지 (file='{}', 현재='{}'). 재빌드 예정.", storedBase, currentBase);
                return null;
            }
            JsonNode entries = root.path("entries");
            if (!entries.isObject()) return null;
            Map<String, String> out = new ConcurrentHashMap<>();
            entries.fields().forEachRemaining(e -> {
                String k = e.getKey();
                String v = e.getValue().asText("");
                if (!k.isEmpty() && !v.isEmpty()) out.put(k, v);
            });
            return out;
        } catch (Exception e) {
            log.warn("인덱스 파일 로드 실패 ({}): {} — 재빌드.", file, e.getMessage());
            return null;
        }
    }

    /** ConcurrentHashMap → 디스크 JSON. 디렉토리 없으면 만들고, 임시 파일에 쓴 뒤 원자적 교체. */
    private void saveIndexToDisk(Map<String, String> idx) {
        Path file = indexFile();
        try {
            Files.createDirectories(file.getParent());
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("schema", INDEX_SCHEMA);
            root.put("builtAt", Instant.now().toString());
            root.put("pokeapiBase", properties.getPokemon().getPokeapiBase());
            root.put("size", idx.size());
            // entries 는 정렬해서 diff 친화적으로
            Map<String, String> sorted = new java.util.TreeMap<>(idx);
            root.put("entries", sorted);

            Path tmp = file.resolveSibling(INDEX_FILE_NAME + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), root);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            log.info("일어 인덱스 디스크 저장 완료: {} ({}개)", file.toAbsolutePath(), idx.size());
        } catch (Exception e) {
            log.warn("인덱스 디스크 저장 실패 ({}): {}", file, e.getMessage());
        }
    }

    public record SpeciesInfo(
            String slug,            // PokéAPI 영문 슬러그 (예: "garchomp")
            String nameKo,          // 한글명 (없으면 영문)
            String nameJa,          // 일어명 (가타카나)
            int generation,         // 등장 세대 (1~9)
            List<String> types,     // 세대 기준 타입
            Stats baseStats,        // 종족값
            String spriteUrl,       // 도트 스프라이트 (작은 사이즈)
            String artworkUrl       // 풀 일러스트 (옵션)
    ) {}

    public record Stats(int hp, int atk, int def, int spa, int spd, int spe) {
        public int total() { return hp + atk + def + spa + spd + spe; }
    }

    /** 약점 한 항목. 2배(mult=2) / 4배(mult=4) 구분해 클라이언트가 강조 표시. */
    public record Weakness(String type, int mult) {}

    private record CacheEntry(SpeciesInfo info, Instant fetchedAt) {}

    /**
     * 영문 슬러그 또는 일어 이름으로 조회. 실패 시 null. 세대 필터 적용 후 반환.
     */
    public SpeciesInfo lookup(String nameRaw, int generation) {
        if (nameRaw == null || nameRaw.isBlank()) return null;
        String slug = resolveSlug(nameRaw);
        if (slug == null) {
            log.debug("Pokemon species 미해결: '{}'", nameRaw);
            return null;
        }
        String cacheKey = slug + "@gen" + generation;
        CacheEntry hit = cache.get(cacheKey);
        long ttlMs = properties.getPokemon().getCacheTtlSec() * 1000L;
        if (hit != null && Duration.between(hit.fetchedAt(), Instant.now()).toMillis() < ttlMs) {
            return hit.info();
        }

        try {
            JsonNode species = getJson("/pokemon-species/" + slug);
            JsonNode pokemon = getJson("/pokemon/" + slug);
            if (species == null || pokemon == null) return null;

            String nameKo = pickLocalizedName(species.path("names"), "ko");
            String nameJa = pickLocalizedName(species.path("names"), "ja-Hrkt");
            if (nameJa.isEmpty()) nameJa = pickLocalizedName(species.path("names"), "ja");
            int genIntroduced = parseGenerationUrl(species.path("generation").path("url").asText(""));

            List<String> types = resolveTypes(pokemon, generation);
            Stats stats = applyPastStats(slug, generation, parseStats(pokemon));
            String sprite = pokemon.path("sprites").path("front_default").asText("");
            String artwork = pokemon.path("sprites").path("other")
                    .path("official-artwork").path("front_default").asText("");

            SpeciesInfo info = new SpeciesInfo(
                    slug,
                    nameKo.isEmpty() ? capitalize(slug) : nameKo,
                    nameJa,
                    genIntroduced,
                    types,
                    stats,
                    sprite,
                    artwork
            );
            cache.put(cacheKey, new CacheEntry(info, Instant.now()));
            return info;
        } catch (Exception e) {
            log.warn("PokéAPI 조회 실패 slug={}: {}", slug, e.getMessage());
            return null;
        }
    }

    /**
     * 입력 q에 매칭되는 포켓몬 이름 후보 반환 (자동완성용).
     * - 인덱스(jaToSlug)에 들어 있는 모든 이름(한글/일어/영문 슬러그)에서 prefix 우선 검색.
     * - 같은 종(slug)은 한 번만 등장. 입력한 형식과 가장 가까운 이름을 우선 표시.
     * - 인덱스 미빌드(워밍업 전)면 빈 리스트.
     * @param q     입력 (마지막 토큰)
     * @param limit 반환 최대 개수
     */
    public List<String> suggest(String q, int limit) {
        if (q == null) return List.of();
        String needle = q.trim().toLowerCase();
        if (needle.isEmpty()) return List.of();
        Map<String, String> idx = jaToSlug;
        if (idx == null || idx.isEmpty()) return List.of();
        if (limit <= 0) limit = 8;

        // 전체 순회 — 컷오프를 두면 ConcurrentHashMap 순서가 비결정적이라 동일 입력에 결과가 달라짐.
        // 인덱스가 ~2600 엔트리(한글/일어/영문) 라 전체 순회 비용은 마이크로초 단위로 무시 가능.
        List<String> starts = new ArrayList<>();
        List<String> contains = new ArrayList<>();
        for (String key : idx.keySet()) {
            String low = key.toLowerCase();
            if (low.startsWith(needle)) starts.add(key);
            else if (low.contains(needle)) contains.add(key);
        }
        // 동일 길이 내에서도 안정적 정렬을 위해 길이 → 이름(locale-insensitive) 순.
        Comparator<String> stable = Comparator.<String>comparingInt(String::length).thenComparing(s -> s);
        starts.sort(stable);
        contains.sort(stable);

        List<String> out = new ArrayList<>();
        Set<String> seenSlugs = new java.util.HashSet<>();
        for (String list = "starts"; list != null; list = list.equals("starts") ? "contains" : null) {
            List<String> src = list.equals("starts") ? starts : contains;
            for (String name : src) {
                if (out.size() >= limit) break;
                String slug = idx.get(name);
                if (slug == null || seenSlugs.contains(slug)) continue;
                seenSlugs.add(slug);
                out.add(name);
            }
            if (out.size() >= limit) break;
        }
        return out;
    }

    /**
     * "garchomp" 그대로 또는 일어/한글 → 영문 슬러그.
     * 인덱스가 아직 비어있으면(워밍업 중) null 반환 — 동기 빌드 절대 안 함.
     * 호출자(PokemonOverlayService.buildCards 등)가 영문 slug 폴백을 시도하게 둠.
     */
    private String resolveSlug(String raw) {
        String trimmed = raw.trim();
        // ASCII 알파벳/숫자/-/_만 있으면 영문 슬러그로 간주
        if (trimmed.chars().allMatch(c -> c < 128 && (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.'))) {
            return trimmed.toLowerCase().replace(' ', '-');
        }
        // 일어/한글/기타 → 인덱스 조회. 비어있으면 미스 (영문 폴백 유도).
        Map<String, String> idx = jaToSlug;
        if (idx == null || idx.isEmpty()) return null;
        return idx.get(trimmed);
    }

    /**
     * PokéAPI의 /pokemon-species?limit=2000 으로 전체 슬러그 목록을 받고, 각각 species 호출해 일어명 매핑.
     * 비용이 크므로 lazy + 영구 캐시. 한 번 만들면 봇 프로세스 수명 동안 재사용.
     *
     * 대안으로 https://pokeapi.co/api/v2/language/ja-Hrkt 의 모든 names를 한번에 못 받기 때문에
     * species 전수 호출이 사실상 유일한 방법. 다만 ko/en/ja 모두 한 species 호출에 들어있어
     * 한번 빌드해두면 다른 언어 모두 활용 가능.
     *
     * 처음 빌드는 수십 초 걸릴 수 있어 백그라운드 + null-safe 사용. 인덱스 없는 동안 일어 입력은 미해결.
     */
    private synchronized Map<String, String> buildJaIndex() {
        if (jaToSlug != null) return jaToSlug;
        log.info("PokéAPI 일어 인덱스 빌드 시작 (수십 초 소요).");
        Map<String, String> idx = new ConcurrentHashMap<>();
        try {
            JsonNode list = getJson("/pokemon-species?limit=2000");
            if (list == null) return idx;
            JsonNode results = list.path("results");
            if (!results.isArray()) return idx;
            int loaded = 0;
            for (JsonNode item : results) {
                String slug = item.path("name").asText("");
                if (slug.isEmpty()) continue;
                try {
                    JsonNode species = getJson("/pokemon-species/" + slug);
                    if (species == null) continue;
                    String nameJa = pickLocalizedName(species.path("names"), "ja-Hrkt");
                    if (!nameJa.isEmpty()) {
                        idx.put(nameJa, slug);
                    }
                    String nameJaPlain = pickLocalizedName(species.path("names"), "ja");
                    if (!nameJaPlain.isEmpty()) {
                        idx.put(nameJaPlain, slug);
                    }
                    String nameKo = pickLocalizedName(species.path("names"), "ko");
                    if (!nameKo.isEmpty()) {
                        idx.put(nameKo, slug);
                    }
                    // 영문 슬러그 자체도 인덱스에 등록 → 자동완성에서 "garc" 같은 입력도 잡힘.
                    idx.put(slug, slug);
                    loaded++;
                } catch (Exception ignored) {
                    // 일부 항목 실패는 인덱스 일부 부족으로 그냥 진행
                }
            }
            log.info("PokéAPI 일어 인덱스 빌드 완료 ({}종)", loaded);
        } catch (Exception e) {
            log.warn("PokéAPI 일어 인덱스 빌드 실패: {}", e.getMessage());
        }
        return idx;
    }

    private JsonNode getJson(String path) throws Exception {
        String base = properties.getPokemon().getPokeapiBase();
        String url = base + path;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.debug("PokéAPI {} status={}", url, resp.statusCode());
            return null;
        }
        return objectMapper.readTree(resp.body());
    }

    private String pickLocalizedName(JsonNode names, String lang) {
        if (!names.isArray()) return "";
        for (JsonNode n : names) {
            String code = n.path("language").path("name").asText("");
            if (code.equalsIgnoreCase(lang)) {
                return n.path("name").asText("");
            }
        }
        return "";
    }

    /** "/generation/8/" → 8. 못 파싱하면 1. */
    private int parseGenerationUrl(String url) {
        if (url == null || url.isEmpty()) return 1;
        try {
            String[] parts = url.replaceAll("/$", "").split("/");
            return Integer.parseInt(parts[parts.length - 1]);
        } catch (Exception e) {
            return 1;
        }
    }

    /**
     * 세대 기준 타입. past_types에 generation 이하 항목이 있으면 그 시점 타입을 사용.
     * past_types는 "이 세대 이하에서는 이 타입이었다" 의미라 generation_of_past <= 목표 세대면 적용.
     */
    private List<String> resolveTypes(JsonNode pokemon, int generation) {
        JsonNode pastTypes = pokemon.path("past_types");
        if (pastTypes.isArray()) {
            for (JsonNode pt : pastTypes) {
                int genCap = parseGenerationUrl(pt.path("generation").path("url").asText(""));
                if (generation <= genCap) {
                    return extractTypeNames(pt.path("types"));
                }
            }
        }
        return extractTypeNames(pokemon.path("types"));
    }

    private List<String> extractTypeNames(JsonNode types) {
        List<String> out = new ArrayList<>();
        if (!types.isArray()) return out;
        for (JsonNode t : types) {
            String name = t.path("type").path("name").asText("");
            if (!name.isEmpty()) out.add(name);
        }
        return out;
    }

    // ---------- 세대별 종족값 override ----------

    /**
     * classpath:pokemon-past-stats.json 로드. 형식 예:
     *   { "butterfree": { "until_gen": 5, "stats": { "spa": 80 } } }
     *   또는 배열: { "slug": [ { "until_gen": 4, "stats": {...} }, { "until_gen": 6, "stats": {...} } ] }
     * "_" 로 시작하는 키는 주석/섹션 마커로 무시.
     * 파일 없거나 파싱 실패 시 빈 매핑 (override 적용 안 됨, 현재값만 사용).
     */
    private void loadPastStatsMappings() {
        try (InputStream is = new ClassPathResource("pokemon-past-stats.json").getInputStream()) {
            JsonNode root = objectMapper.readTree(is);
            Map<String, List<PastStats>> tmp = new HashMap<>();
            root.fields().forEachRemaining(e -> {
                String slug = e.getKey();
                if (slug == null || slug.startsWith("_")) return;
                JsonNode val = e.getValue();
                List<PastStats> list = new ArrayList<>();
                if (val.isArray()) {
                    for (JsonNode n : val) {
                        PastStats p = parsePastStatsEntry(n);
                        if (p != null) list.add(p);
                    }
                } else if (val.isObject()) {
                    PastStats p = parsePastStatsEntry(val);
                    if (p != null) list.add(p);
                }
                if (!list.isEmpty()) {
                    list.sort(Comparator.comparingInt(PastStats::untilGen));
                    tmp.put(slug.toLowerCase(), list);
                }
            });
            pastStatsBySlug = tmp;
            log.info("세대별 종족값 매핑 로드 완료: {}종", tmp.size());
        } catch (Exception e) {
            pastStatsBySlug = Map.of();
            log.warn("pokemon-past-stats.json 로드 실패: {} — override 없이 진행.", e.getMessage());
        }
    }

    private PastStats parsePastStatsEntry(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        int until = node.path("until_gen").asInt(0);
        if (until <= 0) return null;
        JsonNode statsNode = node.path("stats");
        if (!statsNode.isObject()) return null;
        Map<String, Integer> stats = new HashMap<>();
        statsNode.fields().forEachRemaining(e -> {
            String k = e.getKey();
            int v = e.getValue().asInt(-1);
            if (v >= 0) stats.put(k, v);
        });
        if (stats.isEmpty()) return null;
        return new PastStats(until, stats);
    }

    /**
     * 매핑에 등록된 종이면 generation <= until_gen 인 가장 작은 매핑을 적용.
     * 일부 stat 만 적힌 매핑은 그 stat 만 덮어쓰고 나머지는 current 그대로.
     * 매핑 없거나 generation 이 모든 until_gen 보다 크면 current 그대로 반환.
     */
    private Stats applyPastStats(String slug, int generation, Stats current) {
        if (slug == null) return current;
        List<PastStats> entries = pastStatsBySlug.get(slug.toLowerCase());
        if (entries == null || entries.isEmpty()) return current;
        for (PastStats p : entries) {
            if (generation <= p.untilGen()) {
                Map<String, Integer> s = p.stats();
                Stats overridden = new Stats(
                        s.getOrDefault("hp",  current.hp()),
                        s.getOrDefault("atk", current.atk()),
                        s.getOrDefault("def", current.def()),
                        s.getOrDefault("spa", current.spa()),
                        s.getOrDefault("spd", current.spd()),
                        s.getOrDefault("spe", current.spe())
                );
                log.debug("종족값 override 적용 ({} gen={} untilGen={}): {} → {}",
                        slug, generation, p.untilGen(), current, overridden);
                return overridden;
            }
        }
        return current;
    }

    private Stats parseStats(JsonNode pokemon) {
        return new Stats(
                stat(pokemon, "hp"),
                stat(pokemon, "attack"),
                stat(pokemon, "defense"),
                stat(pokemon, "special-attack"),
                stat(pokemon, "special-defense"),
                stat(pokemon, "speed")
        );
    }

    // ---------- 약점 계산 (타입 상성) ----------

    /** 18타입 표준 순서. 세대에 따라 사용 가능한 타입이 다름 (아래 attackTypeExists 참고). */
    private static final List<String> ALL_TYPES = List.of(
            "normal", "fire", "water", "electric", "grass", "ice",
            "fighting", "poison", "ground", "flying", "psychic", "bug",
            "rock", "ghost", "dragon", "dark", "steel", "fairy"
    );

    private record DamageRelations(Set<String> doubleFrom, Set<String> halfFrom, Set<String> noFrom) {
        boolean empty() { return doubleFrom.isEmpty() && halfFrom.isEmpty() && noFrom.isEmpty(); }
    }
    /** current = 현재 데미지 관계, past = 세대 cap → 그 시점 관계. NavigableMap으로 ceilingEntry 가능. */
    private record TypeChart(DamageRelations current, NavigableMap<Integer, DamageRelations> past) {}

    private final Map<String, TypeChart> typeChartCache = new ConcurrentHashMap<>();

    /**
     * 주어진 방어 타입 조합(단일/이중)에 대해 약점(>=2배) 목록 반환. 세대 적용.
     * 정렬: mult 내림차순 → type 알파벳.
     */
    public List<Weakness> computeWeaknesses(List<String> defTypes, int generation) {
        if (defTypes == null || defTypes.isEmpty()) return List.of();
        Map<String, Double> mults = new HashMap<>();
        for (String atk : ALL_TYPES) {
            if (!attackTypeExists(atk, generation)) continue;
            double m = 1.0;
            boolean missing = false;
            for (String def : defTypes) {
                TypeChart chart = fetchTypeChart(def);
                if (chart == null) { missing = true; break; }
                DamageRelations rel = relationsForGen(chart, generation);
                if (rel.doubleFrom().contains(atk)) m *= 2;
                if (rel.halfFrom().contains(atk))   m *= 0.5;
                if (rel.noFrom().contains(atk))     m *= 0;
            }
            if (missing) continue;
            if (m >= 2.0) mults.put(atk, m);
        }
        List<Weakness> out = new ArrayList<>();
        mults.entrySet().stream()
                .sorted(Comparator
                        .<Map.Entry<String, Double>, Double>comparing(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .forEach(e -> out.add(new Weakness(e.getKey(), e.getValue() >= 4.0 ? 4 : 2)));
        return out;
    }

    /** dark/steel은 2세대부터, fairy는 6세대부터. 그 외는 모든 세대 존재. */
    private static boolean attackTypeExists(String type, int gen) {
        if ("dark".equals(type) || "steel".equals(type)) return gen >= 2;
        if ("fairy".equals(type)) return gen >= 6;
        return true;
    }

    private TypeChart fetchTypeChart(String type) {
        TypeChart cached = typeChartCache.get(type);
        if (cached != null) return cached;
        try {
            JsonNode root = getJson("/type/" + type);
            if (root == null) return null;
            DamageRelations cur = parseDamageRelations(root.path("damage_relations"));
            NavigableMap<Integer, DamageRelations> past = new TreeMap<>();
            JsonNode pastArr = root.path("past_damage_relations");
            if (pastArr.isArray()) {
                for (JsonNode p : pastArr) {
                    int genCap = parseGenerationUrl(p.path("generation").path("url").asText(""));
                    DamageRelations rel = parseDamageRelations(p.path("damage_relations"));
                    if (!rel.empty()) past.put(genCap, rel);
                }
            }
            TypeChart chart = new TypeChart(cur, past);
            typeChartCache.put(type, chart);
            return chart;
        } catch (Exception e) {
            log.debug("type chart fetch 실패 {}: {}", type, e.getMessage());
            return null;
        }
    }

    /** past = 세대 cap → 관계. 목표 세대 이상의 첫 키 = 그 시점 관계. 없으면 current. */
    private DamageRelations relationsForGen(TypeChart chart, int generation) {
        Map.Entry<Integer, DamageRelations> e = chart.past().ceilingEntry(generation);
        return e != null ? e.getValue() : chart.current();
    }

    private DamageRelations parseDamageRelations(JsonNode rel) {
        return new DamageRelations(
                namesIn(rel.path("double_damage_from")),
                namesIn(rel.path("half_damage_from")),
                namesIn(rel.path("no_damage_from"))
        );
    }

    private Set<String> namesIn(JsonNode arr) {
        Set<String> out = new HashSet<>();
        if (!arr.isArray()) return out;
        for (JsonNode n : arr) {
            String name = n.path("name").asText("");
            if (!name.isEmpty()) out.add(name);
        }
        return out;
    }

    private int stat(JsonNode pokemon, String key) {
        JsonNode stats = pokemon.path("stats");
        if (!stats.isArray()) return 0;
        for (JsonNode s : stats) {
            if (key.equals(s.path("stat").path("name").asText())) {
                return s.path("base_stat").asInt();
            }
        }
        return 0;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
