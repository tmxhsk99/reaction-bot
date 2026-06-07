package com.jhkim.reactionbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhkim.reactionbot.config.BotProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
    // 일어(가타카나/히라가나) → 영문 슬러그 lazy 인덱스. 처음 요청 시 한 번 빌드.
    private volatile Map<String, String> jaToSlug = null;

    /**
     * overlay 활성 시 startup 시점에 백그라운드로 일어 인덱스 빌드 시작.
     * 일어 우선 lookup 정책이라 인덱스가 없으면 첫 분석이 동기 빌드로 수십 초 멈춤 → 워밍업으로 회피.
     * - overlay.enabled=false 면 아예 시작 안 함 (PokéAPI 1300회 호출 부담 방지)
     * - 가상 스레드라 메인 startup blocking 없음
     * - 빌드 도중 사용자가 분석 호출하면 인덱스 미완성 → 영문 slug 폴백으로 자연스럽게 동작
     */
    @PostConstruct
    public void init() {
        if (!properties.getPokemon().isEnabled() || !properties.getPokemon().getOverlay().isEnabled()) {
            log.info("PokemonSpeciesService: overlay 비활성 — 일어 인덱스 워밍업 생략.");
            return;
        }
        log.info("PokemonSpeciesService: overlay 활성 — 백그라운드 일어 인덱스 워밍업 시작.");
        Thread.startVirtualThread(() -> {
            try {
                Map<String, String> idx = buildJaIndex();
                jaToSlug = idx;
            } catch (Exception e) {
                log.warn("일어 인덱스 백그라운드 워밍업 실패: {}", e.getMessage());
            }
        });
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
            Stats stats = parseStats(pokemon);
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
        if (idx == null) return List.of();
        if (limit <= 0) limit = 8;

        List<String> starts = new ArrayList<>();
        List<String> contains = new ArrayList<>();
        for (String key : idx.keySet()) {
            String low = key.toLowerCase();
            if (low.startsWith(needle)) starts.add(key);
            else if (low.contains(needle)) contains.add(key);
            // 너무 많이 모이면 컷 (성능). limit*8 정도면 정렬 후 충분.
            if (starts.size() + contains.size() > limit * 8) break;
        }
        // 짧은 이름 우선 (예: "잠만보"가 "잠만보(별명)" 같은 변형보다 먼저)
        starts.sort(Comparator.comparingInt(String::length));
        contains.sort(Comparator.comparingInt(String::length));

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

    /** "garchomp" 그대로 또는 일어 "ガブリアス" → 영문 슬러그 */
    private String resolveSlug(String raw) {
        String trimmed = raw.trim();
        // ASCII 알파벳/숫자/-/_만 있으면 영문 슬러그로 간주
        if (trimmed.chars().allMatch(c -> c < 128 && (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.'))) {
            return trimmed.toLowerCase().replace(' ', '-');
        }
        // 일어/한글/기타 → lazy 인덱스 조회
        Map<String, String> idx = jaToSlug;
        if (idx == null) {
            idx = buildJaIndex();
            jaToSlug = idx;
        }
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
