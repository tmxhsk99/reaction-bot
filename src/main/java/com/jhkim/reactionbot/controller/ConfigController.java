package com.jhkim.reactionbot.controller;

import com.jhkim.reactionbot.service.LlmOutputFilter;
import com.jhkim.reactionbot.service.UserConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigController {

    private final UserConfigService userConfig;
    private final LlmOutputFilter outputFilter;

    /** 현재 설정 (시크릿 마스킹). UI가 GET해서 화면에 채움. */
    @GetMapping
    public Map<String, Object> get() {
        return userConfig.readForUi();
    }

    /** UI가 폼 제출. body에 평문 키-값 (마스킹된 값은 무시). */
    @PostMapping
    public Map<String, Object> save(@RequestBody Map<String, Object> incoming) throws IOException {
        Map<String, Object> result = userConfig.write(incoming);
        return Map.of(
                "status", "ok",
                "message", "저장 완료. 적용하려면 서버를 재기동하세요.",
                "savedAt", result.get("saved"));
    }

    /**
     * LLM 출력 비속어 마스킹 사전 조회.
     * 응답: { mappings: {금지어: 대체어, ...}, forbidPatterns: [...], source: "classpath:..." | "file:..." }
     */
    @GetMapping("/profanity-mappings")
    public Map<String, Object> getProfanityMappings() {
        LlmOutputFilter.View v = outputFilter.currentView();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mappings", v.mappings());
        out.put("forbidPatterns", v.forbidPatterns());
        out.put("source", v.source());
        return out;
    }

    /**
     * 매핑 사전 전체 덮어쓰기. cwd 매핑 파일에 dump 하고 즉시 reload(서버 재기동 불필요).
     * body: { mappings: {금지어: 대체어, ...}, forbidPatterns: [...] }
     * 두 필드 모두 optional — 누락 시 빈 값으로 간주.
     */
    @PostMapping("/profanity-mappings")
    public Map<String, Object> saveProfanityMappings(@RequestBody Map<String, Object> body) throws IOException {
        Map<String, String> mappings = coerceStringMap(body.get("mappings"));
        List<String> forbid = coerceStringList(body.get("forbidPatterns"));
        outputFilter.save(mappings, forbid);
        return Map.of(
                "status", "ok",
                "message", "저장 완료. 즉시 반영됨.",
                "mappings", mappings.size(),
                "forbidPatterns", forbid.size());
    }

    private static Map<String, String> coerceStringMap(Object o) {
        Map<String, String> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() == null) continue;
                String k = String.valueOf(e.getKey()).trim();
                String v = e.getValue() == null ? "" : String.valueOf(e.getValue());
                if (k.isEmpty()) continue;
                out.put(k, v);
            }
        }
        return out;
    }

    private static List<String> coerceStringList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object item : l) {
                if (item == null) continue;
                String s = String.valueOf(item).trim();
                if (!s.isEmpty()) out.add(s);
            }
        }
        return out;
    }
}
