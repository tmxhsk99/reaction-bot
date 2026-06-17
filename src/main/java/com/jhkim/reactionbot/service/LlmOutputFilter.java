package com.jhkim.reactionbot.service;

import com.jhkim.reactionbot.config.BotProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 응답 텍스트의 비속어를 TTS 직전에 마스킹/PASS 처리.
 *
 * SpeechPrefilter 가 입력(STT) 측 노이즈 컷이라면, 이쪽은 출력(LLM) 측 안전망.
 * 시스템 프롬프트의 욕설 금지가 지켜지지 않는 경우를 대비.
 *
 * 매핑 사전 로딩:
 *   1. cwd 의 사용자 매핑 파일이 있으면 그것만 사용 (단독 source).
 *      경로: BotProperties.speech.profanity-filter.mappings-file. 비어있으면 "./profanity-mappings.yml".
 *   2. 없으면 classpath:profanity-mappings.yml 디폴트 사용.
 *
 *   /config UI 에서 매핑을 저장하면 cwd 파일에 dump → 다음 reload 부터 그 파일 사용.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmOutputFilter {

    private static final String CLASSPATH_DEFAULT = "profanity-mappings.yml";
    private static final String DEFAULT_USER_FILE = "./profanity-mappings.yml";
    // forbid-patterns 안전 가드: 입력자가 운영자 본인이라도 catastrophic regex 가
    // TTS 스레드를 막지 못하게 개수·길이 상한을 둠.
    private static final int MAX_FORBID_PATTERNS = 100;
    private static final int MAX_PATTERN_LENGTH = 1000;

    private final BotProperties properties;

    @Value("${user.dir:.}")
    private String workingDir;

    // 현재 적용 중인 사전 (snapshot). 동시성: 읽기 빈도가 압도적이라 volatile snapshot 교체.
    private volatile Snapshot snapshot = Snapshot.empty();

    @PostConstruct
    void init() {
        reload();
    }

    /** 사전을 파일에서 다시 로드해 in-memory 컴파일된 매처를 교체. */
    public synchronized void reload() {
        BotProperties.ProfanityFilter cfg = properties.getSpeech().getProfanityFilter();
        if (cfg == null || !cfg.isEnabled()) {
            snapshot = Snapshot.empty();
            log.info("LlmOutputFilter 비활성 (enabled=false)");
            return;
        }

        Mappings raw = loadFromFiles();
        snapshot = compile(raw, cfg.isWholeWord());
        log.info("LlmOutputFilter 준비: mode={}, mappings={}, forbid-patterns={}, whole-word={}, source={}",
                cfg.getMode(), snapshot.mappings.size(), snapshot.forbidPatterns.size(),
                cfg.isWholeWord(), raw.source);
    }

    /**
     * 현재 사전을 사용자 파일(cwd)에 저장하고 즉시 reload.
     * /config UI 가 호출. 저장 후엔 cwd 파일이 단독 source 가 됨.
     */
    public synchronized void save(Map<String, String> mappings, List<String> forbidPatterns) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("mappings", mappings == null ? new LinkedHashMap<>() : new LinkedHashMap<>(mappings));
        root.put("forbid-patterns", forbidPatterns == null ? new ArrayList<>() : new ArrayList<>(forbidPatterns));

        Path file = userMappingFile();
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        DumperOptions opt = new DumperOptions();
        opt.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opt.setPrettyFlow(true);
        opt.setIndent(2);
        opt.setAllowUnicode(true);
        Yaml yaml = new Yaml(opt);
        try (OutputStream os = Files.newOutputStream(file)) {
            os.write(("# /config UI 가 저장한 비속어 매핑 사전. classpath 디폴트 대신 이 파일이 단독 source.\n"
                    + "# 삭제하면 다음 기동/리로드 시 classpath:profanity-mappings.yml 로 복귀.\n")
                    .getBytes(StandardCharsets.UTF_8));
            yaml.dump(root, new OutputStreamWriter(os, StandardCharsets.UTF_8));
        }
        log.info("profanity-mappings 저장: {} (mappings={}, forbid={})",
                file.toAbsolutePath(), mappings == null ? 0 : mappings.size(),
                forbidPatterns == null ? 0 : forbidPatterns.size());
        reload();
    }

    /** UI 표시용: 현재 적용 중인 사전과 source(파일 경로/classpath) 반환. */
    public synchronized View currentView() {
        Mappings raw = loadFromFiles();
        return new View(raw.mappings, raw.forbidPatterns, raw.source);
    }

    public Path userMappingFile() {
        String configured = properties.getSpeech().getProfanityFilter().getMappingsFile();
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured);
        }
        return Paths.get(workingDir, DEFAULT_USER_FILE.substring(2));   // "./" 제거
    }

    /** 사용자 파일이 있으면 그것만, 없으면 classpath 디폴트 로드. */
    private Mappings loadFromFiles() {
        Path userFile = userMappingFile();
        if (Files.exists(userFile)) {
            try (InputStream is = Files.newInputStream(userFile)) {
                return parse(is, "file:" + userFile.toAbsolutePath());
            } catch (Exception e) {
                log.warn("사용자 매핑 파일 로드 실패 — classpath 디폴트로 폴백: {} ({})",
                        userFile, e.getMessage());
            }
        }
        try (InputStream is = new ClassPathResource(CLASSPATH_DEFAULT).getInputStream()) {
            return parse(is, "classpath:" + CLASSPATH_DEFAULT);
        } catch (Exception e) {
            log.warn("classpath 디폴트 매핑 로드 실패 — 빈 사전: {}", e.getMessage());
            return new Mappings(new LinkedHashMap<>(), new ArrayList<>(), "empty");
        }
    }

    @SuppressWarnings("unchecked")
    private static Mappings parse(InputStream is, String source) {
        Yaml yaml = new Yaml();
        Object loaded = yaml.load(is);
        Map<String, String> mappings = new LinkedHashMap<>();
        List<String> forbid = new ArrayList<>();
        if (loaded instanceof Map<?, ?> m) {
            Object rawMap = m.get("mappings");
            if (rawMap instanceof Map<?, ?> mm) {
                for (Map.Entry<?, ?> e : mm.entrySet()) {
                    if (e.getKey() == null) continue;
                    mappings.put(String.valueOf(e.getKey()), e.getValue() == null ? "" : String.valueOf(e.getValue()));
                }
            }
            Object rawList = m.get("forbid-patterns");
            if (rawList instanceof List<?> ll) {
                for (Object o : ll) if (o != null) forbid.add(String.valueOf(o));
            }
        }
        return new Mappings(mappings, forbid, source);
    }

    private static Snapshot compile(Mappings raw, boolean wholeWord) {
        List<Mapping> compiled = new ArrayList<>();
        for (Map.Entry<String, String> e : raw.mappings.entrySet()) {
            String key = e.getKey();
            String val = e.getValue() == null ? "" : e.getValue();
            if (key == null || key.isEmpty()) continue;
            compiled.add(new Mapping(key, val, buildMatchPattern(key, wholeWord)));
        }
        List<Pattern> forbid = new ArrayList<>();
        int accepted = 0;
        for (String raw0 : raw.forbidPatterns) {
            if (raw0 == null || raw0.isBlank()) continue;
            if (accepted >= MAX_FORBID_PATTERNS) {
                log.warn("forbid-patterns 개수 한도({}) 초과 — 나머지 스킵", MAX_FORBID_PATTERNS);
                break;
            }
            if (raw0.length() > MAX_PATTERN_LENGTH) {
                log.warn("forbid-patterns 길이 한도({}) 초과 — 스킵: '{}...'",
                        MAX_PATTERN_LENGTH, raw0.substring(0, Math.min(40, raw0.length())));
                continue;
            }
            try {
                forbid.add(Pattern.compile(raw0, Pattern.CASE_INSENSITIVE));
                accepted++;
            } catch (Exception ex) {
                log.warn("forbid-patterns 컴파일 실패 — 스킵: '{}': {}", raw0, ex.getMessage());
            }
        }
        return new Snapshot(compiled, forbid);
    }

    /**
     * whole-word=true 이고 key 가 한글 1글자면 앞뒤가 한글일 때만 매칭(어절 내부 박힘 보호).
     * 그 외엔 단순 부분 매칭. 모두 CASE_INSENSITIVE.
     */
    private static Pattern buildMatchPattern(String key, boolean wholeWord) {
        String quoted = Pattern.quote(key);
        if (wholeWord && key.length() == 1 && isHangul(key.charAt(0))) {
            return Pattern.compile("(?<=[가-힣])" + quoted + "(?=[가-힣])");
        }
        return Pattern.compile(quoted, Pattern.CASE_INSENSITIVE);
    }

    private static boolean isHangul(char c) {
        return c >= '가' && c <= '힣';
    }

    /**
     * LLM 응답 텍스트에 필터 적용.
     *  - UNCHANGED : 비속어 없음. 원본 그대로 발화.
     *  - MASKED    : 매핑 치환 후 발화. result.text 가 치환된 결과.
     *  - PASS      : 발화 차단. result.text 는 null.
     */
    public Result apply(String text) {
        if (text == null || text.isEmpty()) return new Result(Action.UNCHANGED, text, null);
        BotProperties.ProfanityFilter cfg = properties.getSpeech().getProfanityFilter();
        if (cfg == null || !cfg.isEnabled()) return new Result(Action.UNCHANGED, text, null);

        Snapshot snap = this.snapshot;

        for (Pattern p : snap.forbidPatterns) {
            if (p.matcher(text).find()) {
                return new Result(Action.PASS, null, "pattern:" + p.pattern());
            }
        }

        boolean maskMode = !"pass".equalsIgnoreCase(cfg.getMode());

        String result = text;
        List<String> hits = new ArrayList<>();
        for (Mapping mp : snap.mappings) {
            String replaced = mp.pattern.matcher(result)
                    .replaceAll(Matcher.quoteReplacement(mp.replacement));
            if (!replaced.equals(result)) {
                hits.add(mp.key + "→" + mp.replacement);
                result = replaced;
            }
        }

        if (hits.isEmpty()) return new Result(Action.UNCHANGED, text, null);
        if (!maskMode)     return new Result(Action.PASS, null, String.join(",", hits));
        return new Result(Action.MASKED, result, String.join(",", hits));
    }

    public enum Action { UNCHANGED, MASKED, PASS }

    public record Result(Action action, String text, String hit) {}

    /** /config UI 응답 DTO. */
    public record View(Map<String, String> mappings, List<String> forbidPatterns, String source) {}

    private record Mapping(String key, String replacement, Pattern pattern) {}

    private record Mappings(Map<String, String> mappings, List<String> forbidPatterns, String source) {}

    private record Snapshot(List<Mapping> mappings, List<Pattern> forbidPatterns) {
        static Snapshot empty() { return new Snapshot(List.of(), List.of()); }
    }
}
