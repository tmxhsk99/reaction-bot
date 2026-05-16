package com.jhkim.reactionbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhkim.reactionbot.config.BotProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 한국어 발화에서 포켓몬 이름을 감지하고, PokeAPI에서 정보를 받아와
 * Claude LLM에 주입할 컨텍스트 문자열로 포맷한다.
 *
 * 흐름: STT 텍스트 → 사전 매칭으로 한·영 변환 → PokeAPI fetch(캐시) → 요약 컨텍스트
 *
 * 사전은 resources/pokemon-ko-en.json. 그곳에 한 줄 추가하면 새 이름 자동 인식.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PokemonContextService {

    private final BotProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private Map<String, String> koToEn = new HashMap<>();
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(String summary, Instant fetchedAt) {}

    @PostConstruct
    public void init() {
        if (!properties.getPokemon().isEnabled()) {
            log.info("Pokemon context 비활성");
            return;
        }
        try (InputStream is = new ClassPathResource("pokemon-ko-en.json").getInputStream()) {
            JsonNode root = objectMapper.readTree(is);
            Map<String, String> tmp = new HashMap<>();
            root.fieldNames().forEachRemaining(k -> {
                if (k.startsWith("_")) return;
                tmp.put(k, root.get(k).asText());
            });
            koToEn = tmp;
            log.info("Pokemon 사전 로드 완료: {}개", koToEn.size());
        } catch (Exception e) {
            log.warn("pokemon-ko-en.json 로드 실패 - 포켓몬 컨텍스트 비활성: {}", e.getMessage());
        }
    }

    /**
     * 발화 텍스트에서 포켓몬 이름들을 감지해 컨텍스트 문자열 반환.
     * 매칭 없으면 null.
     */
    public String buildContext(String userText) {
        if (!properties.getPokemon().isEnabled()) return null;
        if (userText == null || userText.isBlank() || koToEn.isEmpty()) return null;

        Set<String> hits = new LinkedHashSet<>();
        for (String koName : koToEn.keySet()) {
            if (userText.contains(koName)) {
                hits.add(koName);
                if (hits.size() >= 3) break; // 한 발화에 너무 많은 포켓몬은 컷
            }
        }
        if (hits.isEmpty()) return null;

        List<String> summaries = new ArrayList<>();
        for (String ko : hits) {
            String summary = fetchSummary(ko, koToEn.get(ko));
            if (summary != null) summaries.add(summary);
        }
        if (summaries.isEmpty()) return null;

        return "[참고용 포켓몬 정보 - 자연스럽게 활용하되 정보를 그대로 읽지 마라]\n"
                + String.join("\n", summaries);
    }

    private String fetchSummary(String koName, String enName) {
        CacheEntry cached = cache.get(enName);
        long ttlMs = properties.getPokemon().getCacheTtlSec() * 1000L;
        if (cached != null && Duration.between(cached.fetchedAt(), Instant.now()).toMillis() < ttlMs) {
            return cached.summary();
        }
        try {
            String url = properties.getPokemon().getPokeapiBase() + "/pokemon/" + enName;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.debug("PokeAPI {}={} status={}", koName, enName, resp.statusCode());
                return null;
            }

            JsonNode root = objectMapper.readTree(resp.body());
            String types = streamFieldList(root.path("types"), "type", "name");
            String abilities = streamFieldList(root.path("abilities"), "ability", "name");
            int hp = statValue(root, "hp");
            int atk = statValue(root, "attack");
            int def = statValue(root, "defense");
            int spa = statValue(root, "special-attack");
            int spd = statValue(root, "special-defense");
            int spe = statValue(root, "speed");
            int total = hp + atk + def + spa + spd + spe;

            String summary = String.format(
                    "- %s (영문 %s): 타입=%s, 특성=%s, 종족값 합=%d (HP%d/공%d/방%d/특공%d/특방%d/스핏%d)",
                    koName, enName, types, abilities, total, hp, atk, def, spa, spd, spe);
            cache.put(enName, new CacheEntry(summary, Instant.now()));
            log.debug("PokeAPI fetched: {} → {}", koName, summary);
            return summary;
        } catch (Exception e) {
            log.debug("PokeAPI 호출 실패 {}={}: {}", koName, enName, e.getMessage());
            return null;
        }
    }

    private String streamFieldList(JsonNode arr, String key, String subKey) {
        if (!arr.isArray()) return "";
        List<String> names = new ArrayList<>();
        arr.forEach(n -> {
            String v = n.path(key).path(subKey).asText("");
            if (!v.isEmpty()) names.add(v);
        });
        return names.stream().collect(Collectors.joining("/"));
    }

    private int statValue(JsonNode root, String statName) {
        JsonNode stats = root.path("stats");
        if (!stats.isArray()) return 0;
        for (JsonNode s : stats) {
            if (statName.equals(s.path("stat").path("name").asText())) {
                return s.path("base_stat").asInt();
            }
        }
        return 0;
    }
}
