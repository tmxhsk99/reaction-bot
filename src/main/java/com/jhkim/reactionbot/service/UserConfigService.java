package com.jhkim.reactionbot.service;

import com.jhkim.reactionbot.config.BotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 사용자가 UI(/config)에서 변경한 설정값을 jar 옆 config.yml에 영구화.
 *
 * - start.bat이 java 실행 시 --spring.config.additional-location=file:./config.yml 옵션을 주면
 *   다음 기동 때 자동으로 머지됨 (override priority가 높음).
 * - 화면에 보여줄 때는 비밀 값은 마스킹해서 반환.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserConfigService {

    private static final String FILE_NAME = "config.yml";

    // UI에서 편집 가능한 키 (화이트리스트)
    private static final List<String> EDITABLE_KEYS = List.of(
            "reaction-bot.mode",
            "reaction-bot.llm.provider",
            "reaction-bot.anthropic.api-key",
            "reaction-bot.gemini.api-key",
            "reaction-bot.openai.api-key",
            "reaction-bot.claude-cli.executable",
            "reaction-bot.claude-cli.executable-search-dir",
            "reaction-bot.codex-cli.executable",
            "reaction-bot.codex-cli.api-key",
            "reaction-bot.tts.voice",
            "reaction-bot.character.name",
            "reaction-bot.character.streamer-name",
            "reaction-bot.character.use-custom-prompt",
            "reaction-bot.character.custom-identity",
            "reaction-bot.character.custom-personality",
            "reaction-bot.character.custom-rules",
            "reaction-bot.screen.source",
            "reaction-bot.screen.obs.password",
            "reaction-bot.idle-trigger.enabled",
            "reaction-bot.speech.respond-only-when-addressed",
            "reaction-bot.speech.profanity-filter.enabled",
            "reaction-bot.speech.profanity-filter.mode",
            "reaction-bot.stt.auto-start",
            "reaction-bot.stt.device-index",
            "reaction-bot.pokemon.enabled",
            "reaction-bot.pokemon.overlay.enabled",
            "reaction-bot.pokemon.overlay.generation",
            "reaction-bot.pokemon.overlay.mode",
            "reaction-bot.pokemon.overlay.refresh-interval-ms",
            "reaction-bot.pokemon.overlay.max-pokemon",
            "reaction-bot.pokemon.overlay.infer-moves",
            "reaction-bot.screen-translate.source-langs",
            "reaction-bot.screen-translate.target-lang",
            "reaction-bot.screen-translate.auto-mode",
            "reaction-bot.screen-translate.interval-ms",
            "reaction-bot.screen-translate.dialogue-only",
            "reaction-bot.screen-translate.tts-enabled",
            "reaction-bot.screen-translate.lines-per-page",
            "reaction-bot.screen-translate.capture-mode",
            "reaction-bot.screen-translate.crop-region",
            "reaction-bot.screen-translate.blank-luma-stddev",
            "reaction-bot.screen-translate.hash-stability-threshold",
            "reaction-bot.screen-translate.require-frame-stability",
            "reaction-bot.screen-translate.translation-dedup-similarity",
            "reaction-bot.screen-translate.target-width"
    );

    // 빈 문자열로의 변경을 허용하는 키. (기본 정책은 빈 값=변경 안 함 이지만 텍스트영역/경로는 지워서 자동 탐색 복귀 가능해야 함)
    private static final List<String> ALLOW_EMPTY_KEYS = List.of(
            "reaction-bot.character.custom-identity",
            "reaction-bot.character.custom-personality",
            "reaction-bot.character.custom-rules",
            "reaction-bot.claude-cli.executable",
            "reaction-bot.claude-cli.executable-search-dir",
            "reaction-bot.codex-cli.executable",
            // crop-region 은 ""=fullscreen fallback 으로 되돌리는 의미
            "reaction-bot.screen-translate.crop-region"
    );

    // 빈 값으로 보내면 "변경 안 함"이 아니라 키 자체를 지워서 null/기본값으로 되돌리는 키.
    // (device-index는 nullable Integer라 "" 그대로 저장하면 바인딩이 깨지므로 키를 제거)
    private static final List<String> NULL_ON_EMPTY_KEYS = List.of(
            "reaction-bot.stt.device-index"
    );

    // 마스킹할 시크릿 키
    private static final List<String> SECRET_KEYS = List.of(
            "reaction-bot.anthropic.api-key",
            "reaction-bot.gemini.api-key",
            "reaction-bot.openai.api-key",
            "reaction-bot.codex-cli.api-key",
            "reaction-bot.screen.obs.password"
    );

    private final BotProperties properties;

    @Value("${user.dir:.}")
    private String workingDir;

    /** UI 표시용 — 현재 값(런타임 BotProperties 기준), 시크릿은 마스킹. */
    public Map<String, Object> readForUi() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reaction-bot.mode", safe(properties.getMode()));
        out.put("reaction-bot.llm.provider", safe(properties.getLlm().getProvider()));
        out.put("reaction-bot.anthropic.api-key", maskSecret(safe(properties.getAnthropic().getApiKey())));
        out.put("reaction-bot.gemini.api-key", maskSecret(safe(properties.getGemini().getApiKey())));
        out.put("reaction-bot.openai.api-key", maskSecret(safe(properties.getOpenai().getApiKey())));
        out.put("reaction-bot.claude-cli.executable", safe(properties.getClaudeCli().getExecutable()));
        out.put("reaction-bot.claude-cli.executable-search-dir", safe(properties.getClaudeCli().getExecutableSearchDir()));
        out.put("reaction-bot.codex-cli.executable", safe(properties.getCodexCli().getExecutable()));
        out.put("reaction-bot.codex-cli.api-key", maskSecret(safe(properties.getCodexCli().getApiKey())));
        out.put("reaction-bot.tts.voice", safe(properties.getTts().getVoice()));
        out.put("reaction-bot.character.name", safe(properties.getCharacter().getName()));
        out.put("reaction-bot.character.streamer-name", safe(properties.getCharacter().getStreamerName()));
        out.put("reaction-bot.character.use-custom-prompt", properties.getCharacter().isUseCustomPrompt());
        out.put("reaction-bot.character.custom-identity", safe(properties.getCharacter().getCustomIdentity()));
        out.put("reaction-bot.character.custom-personality", safe(properties.getCharacter().getCustomPersonality()));
        out.put("reaction-bot.character.custom-rules", safe(properties.getCharacter().getCustomRules()));
        out.put("reaction-bot.screen.source", safe(properties.getScreen().getSource()));
        out.put("reaction-bot.screen.obs.password", maskSecret(safe(properties.getScreen().getObs().getPassword())));
        out.put("reaction-bot.idle-trigger.enabled", properties.getIdleTrigger().isEnabled());
        out.put("reaction-bot.speech.respond-only-when-addressed", properties.getSpeech().isRespondOnlyWhenAddressed());
        out.put("reaction-bot.speech.profanity-filter.enabled", properties.getSpeech().getProfanityFilter().isEnabled());
        out.put("reaction-bot.speech.profanity-filter.mode", safe(properties.getSpeech().getProfanityFilter().getMode()));
        out.put("reaction-bot.stt.auto-start", properties.getStt().isAutoStart());
        out.put("reaction-bot.stt.device-index",
                properties.getStt().getDeviceIndex() == null ? "" : String.valueOf(properties.getStt().getDeviceIndex()));
        out.put("reaction-bot.pokemon.enabled", properties.getPokemon().isEnabled());
        out.put("reaction-bot.pokemon.overlay.enabled", properties.getPokemon().getOverlay().isEnabled());
        out.put("reaction-bot.pokemon.overlay.generation", properties.getPokemon().getOverlay().getGeneration());
        out.put("reaction-bot.pokemon.overlay.mode", safe(properties.getPokemon().getOverlay().getMode()));
        out.put("reaction-bot.pokemon.overlay.refresh-interval-ms", properties.getPokemon().getOverlay().getRefreshIntervalMs());
        out.put("reaction-bot.pokemon.overlay.max-pokemon", properties.getPokemon().getOverlay().getMaxPokemon());
        out.put("reaction-bot.pokemon.overlay.infer-moves", properties.getPokemon().getOverlay().isInferMoves());
        BotProperties.ScreenTranslate st = properties.getScreenTranslate();
        out.put("reaction-bot.screen-translate.source-langs", st.getSourceLangs());
        out.put("reaction-bot.screen-translate.target-lang", safe(st.getTargetLang()));
        out.put("reaction-bot.screen-translate.auto-mode", st.isAutoMode());
        out.put("reaction-bot.screen-translate.interval-ms", st.getIntervalMs());
        out.put("reaction-bot.screen-translate.dialogue-only", st.isDialogueOnly());
        out.put("reaction-bot.screen-translate.tts-enabled", st.isTtsEnabled());
        out.put("reaction-bot.screen-translate.lines-per-page", st.getLinesPerPage());
        out.put("reaction-bot.screen-translate.capture-mode", safe(st.getCaptureMode()));
        out.put("reaction-bot.screen-translate.crop-region", safe(st.getCropRegion()));
        out.put("reaction-bot.screen-translate.blank-luma-stddev", st.getBlankLumaStddev());
        out.put("reaction-bot.screen-translate.hash-stability-threshold", st.getHashStabilityThreshold());
        out.put("reaction-bot.screen-translate.require-frame-stability", st.isRequireFrameStability());
        out.put("reaction-bot.screen-translate.translation-dedup-similarity", st.getTranslationDedupSimilarity());
        out.put("reaction-bot.screen-translate.target-width", st.getTargetWidth());
        return out;
    }

    /** 사용자가 보낸 값을 받아 config.yml에 저장. 마스킹된 값(****)이 오면 무시(기존 유지). */
    public synchronized Map<String, Object> write(Map<String, Object> incoming) throws IOException {
        Path file = configFile();
        Map<String, Object> existing = loadYaml(file);

        for (Map.Entry<String, Object> e : incoming.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (!EDITABLE_KEYS.contains(key)) continue;
            if (value == null) continue;
            if (value instanceof String s) {
                // 마스킹된 그대로 들어온 경우 → 변경 의도 아님
                if (s.matches("^\\*{2,}.*")) continue;
                if (s.isEmpty()) {
                    if (NULL_ON_EMPTY_KEYS.contains(key)) {
                        removeNested(existing, key);
                        continue;
                    }
                    // 일반 키는 빈 값=변경 안 함. ALLOW_EMPTY_KEYS만 빈 값 저장 허용(텍스트영역 지우기).
                    if (!ALLOW_EMPTY_KEYS.contains(key)) continue;
                }
            }
            setNested(existing, key, value);
        }
        saveYaml(file, existing);
        log.info("config.yml 저장: {}", file.toAbsolutePath());
        return Map.of("saved", file.toAbsolutePath().toString());
    }

    public Path configFile() {
        return Paths.get(workingDir, FILE_NAME);
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String maskSecret(String s) {
        if (s == null || s.isEmpty()) return "";
        if (s.length() <= 4) return "****";
        return "****" + s.substring(s.length() - 4);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Path file) {
        if (!Files.exists(file)) return new LinkedHashMap<>();
        try (InputStream is = Files.newInputStream(file)) {
            Yaml yaml = new Yaml();
            Object loaded = yaml.load(is);
            return loaded instanceof Map ? new LinkedHashMap<>((Map<String, Object>) loaded) : new LinkedHashMap<>();
        } catch (Exception e) {
            log.warn("config.yml 로드 실패 - 빈 맵으로 시작: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private static void saveYaml(Path file, Map<String, Object> map) throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        Yaml yaml = new Yaml(options);
        try (OutputStream os = Files.newOutputStream(file)) {
            os.write(("# reaction-bot 사용자 설정. /config UI 에서 편집되거나 직접 수정 가능.\n"
                    + "# Spring Boot 기동 시 --spring.config.additional-location=file:./config.yml 로 머지됨.\n")
                    .getBytes());
            yaml.dump(map, new java.io.OutputStreamWriter(os, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @SuppressWarnings("unchecked")
    private static void setNested(Map<String, Object> root, String dottedKey, Object value) {
        String[] parts = dottedKey.split("\\.");
        Map<String, Object> cur = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = cur.get(parts[i]);
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<String, Object>();
                cur.put(parts[i], next);
            }
            cur = (Map<String, Object>) next;
        }
        cur.put(parts[parts.length - 1], value);
    }

    @SuppressWarnings("unchecked")
    private static void removeNested(Map<String, Object> root, String dottedKey) {
        String[] parts = dottedKey.split("\\.");
        Map<String, Object> cur = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = cur.get(parts[i]);
            if (!(next instanceof Map)) return;
            cur = (Map<String, Object>) next;
        }
        cur.remove(parts[parts.length - 1]);
    }
}
