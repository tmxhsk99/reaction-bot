package com.jhkim.reactionbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhkim.reactionbot.config.BotProperties;
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
import java.util.List;
import java.util.Map;
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
