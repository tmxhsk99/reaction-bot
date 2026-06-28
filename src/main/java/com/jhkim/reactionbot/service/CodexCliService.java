package com.jhkim.reactionbot.service;

import com.jhkim.reactionbot.config.BotProperties;
import com.jhkim.reactionbot.config.CharacterConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Codex CLI(OpenAI) 백엔드. 봇이 subprocess로 `codex exec`를 호출해 응답을 받는다.
 *
 * 동기:
 *   ChatGPT 구독(`codex login`) 한도 안에서 처리 → 호출 비용 0. 또는 CODEX_API_KEY로 종량 과금.
 *
 * 동작:
 *   1) base64 JPEG → 임시 파일로 디코드 저장 → `-i <경로>`로 직접 첨부 (Read 도구 불필요)
 *   2) 캐릭터 페르소나/지침을 프롬프트 본문 앞에 주입 (codex엔 --system-prompt 가 없음)
 *      → codex의 "코딩 에이전트" 기본 프롬프트가 항상 깔리므로 페르소나를 본문에서 강하게 못박음
 *   3) 프롬프트는 stdin으로 전달 (`codex exec ... -`), 최종 메시지는 `-o <임시파일>`로 캡처
 *   4) 출력 sanitize → 히스토리 저장 → orchestrator가 TTS
 *   5) 임시 이미지/출력 파일은 finally에서 항상 삭제
 *
 * 특성:
 *   - subprocess 오버헤드가 크므로 hasSeparateTriage=triage-enabled (기본 1회 호출)
 *   - acceptsImage=true. 화면 캡처는 orchestrator가 담당
 *   - 출력 정제는 ClaudeCliService와 동일 로직(라벨 prefix·따옴표·문장 수 컷)
 *
 * reaction-bot.llm.provider=codex-cli 일 때만 빈 생성.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "reaction-bot.llm", name = "provider", havingValue = "codex-cli")
public class CodexCliService implements LlmProvider {

    private static final Pattern LEADING_LABEL =
            Pattern.compile("^\\s*(?:[\\[\\(]\\s*[^\\]\\)\\r\\n]{1,20}\\s*[\\]\\)]|\\*{1,2}[^*\\r\\n]{1,20}\\*{1,2}|[\\p{L}]{1,10})\\s*[:：]\\s*");
    private static final Pattern WRAPPING_QUOTES =
            Pattern.compile("^[\"'“”‘’`]+|[\"'“”‘’`]+$");

    private static final int MAX_SENTENCES = 2;

    private static final String ASSERTIVE_NUDGE = """


            [Codex 모드 추가 지침]
            - 평소보다 한 발 더 적극적으로 끼어들어라.
            - "PASS 3가지 조건"이 명확히 충족될 때만 PASS. 애매하면 한 마디.
            - 매번 다른 각도(놀림/응원/예측/딴지/자랑)로 변주.
            """;

    private static final String TRIAGE_SYSTEM = """
            당신은 스트리밍 코멘터리 봇의 판단기입니다.
            방금 스트리머가 한 말(텍스트)만 보고, 봇이 "지금 한 마디 해도 될 상황인가" 판단하세요.
            (비용 절감을 위해 1차 판단에는 화면 이미지가 제공되지 않습니다. 텍스트만으로 보수적으로 판단.)
            답은 정확히 SPEAK 또는 PASS 둘 중 하나. 다른 글자 절대 금지.

            SPEAK 기준:
              - 게임에 의미있는 사건이 있고 스트리머도 그에 대해 말함
              - 스트리머가 명확히 반응 유도 ("야 봐", "오 대박", "이거 뭐야")
              - 한참 조용했다가 의미 있는 발화

            PASS 기준 (대부분 PASS):
              - 시청자에게 안내/설명하는 중
              - 게임 NPC/캐릭터 대사 따라하기
              - 의미 없는 추임새, 헛기침
              - 단순 화면 상태 언급만
              - 애매하면 PASS. 침묵이 미덕.
            """;

    private static final String TRIAGE_SYSTEM_WITH_VISION = """
            당신은 스트리밍 코멘터리 봇의 판단기입니다.
            방금 스트리머가 한 말(텍스트)만 보고 다음 3가지 중 하나를 선택하세요.
            답은 정확히 다음 라벨 셋 중 하나. 다른 글자 절대 금지: PASS | SPEAK_VISION | SPEAK_TEXT

            PASS:
              - 시청자에게 안내/설명, NPC 대사 따라하기, 단순 추임새, 단순 화면 상태 언급
              - 애매하면 PASS

            SPEAK_VISION (화면을 봐야 답할 수 있는 경우):
              - 게임 사건에 대한 반응 ("오 대박", "이거 뭐야", "여기로 가야 되나")
              - 화면의 무언가를 지칭/질문 ("이거 어때?", "저거 뭐였지?")
              - 스트리머가 "야 봐", "이거 봐바" 류로 화면을 가리킴

            SPEAK_TEXT (화면 없이도 답할 수 있는 경우):
              - 봇 이름 호명 + 텍스트 질문 ("{name}아 너는 뭐 좋아해?", "{name} 잘 지냈어?")
              - 봇 의견·생각을 묻는 일반 잡담 ("너는 어떻게 생각해?")
              - 화면 맥락 없는 자기 얘기·회상
            """;

    private final BotProperties properties;
    private final CharacterConfig character;
    private final ConversationHistory history;
    private final PokemonContextService pokemonContext;
    private final PassCounter passCounter;

    @PostConstruct
    public void init() {
        BotProperties.CodexCli cfg = properties.getCodexCli();
        boolean usingApiKey = !isBlank(cfg.getApiKey());
        log.info("LLM provider=codex-cli. exec={}, 인증={}, model={}, reasoning={}, workingDir={}, timeout={}s",
                cfg.getExecutable(),
                usingApiKey ? "CODEX_API_KEY(종량)" : "codex login(구독)",
                isBlank(cfg.getModel()) ? "(codex 기본)" : cfg.getModel(),
                isBlank(cfg.getReasoningEffort()) ? "(codex 기본)" : cfg.getReasoningEffort(),
                isBlank(cfg.getWorkingDir()) ? "(봇 cwd)" : cfg.getWorkingDir(),
                cfg.getTimeoutSec());
    }

    @Override
    public boolean hasSeparateTriage() {
        return properties.getCodexCli().isTriageEnabled();
    }

    @Override
    public boolean acceptsImage() {
        return true;
    }

    @Override
    public TriageResult triage(String userText, boolean needsVisionDecision) {
        String input = (userText == null || userText.isBlank()) ? "(자동 트리거)" : userText;
        BotProperties.CodexCli cfg = properties.getCodexCli();

        String basePrompt = character.substitute(needsVisionDecision ? TRIAGE_SYSTEM_WITH_VISION : TRIAGE_SYSTEM);
        String systemPrompt = basePrompt + passCounter.buildNudge("triage");
        // codex엔 system/user 분리가 없으므로 지침 + 입력을 한 본문으로 합성.
        String prompt = systemPrompt + "\n\n[방금 들은 말]\n" + input;

        String model = isBlank(cfg.getTriageModel()) ? cfg.getModel() : cfg.getTriageModel();

        String raw;
        try {
            raw = callCodex(cfg, prompt, model, null, cfg.getTriageTimeoutSec());
        } catch (Exception e) {
            log.warn("Triage codex 호출 실패. SPEAK_WITH_VISION로 폴백: {}", e.getMessage());
            return TriageResult.SPEAK_WITH_VISION;
        }

        String normalized = raw.toUpperCase().replaceAll("[^A-Z_]", "");
        TriageResult result = parseTriageResult(normalized, needsVisionDecision);
        log.info("Triage 결과 (codex): {} (raw='{}', 연속PASS={})", result, normalized, passCounter.get());

        if (result == TriageResult.PASS) passCounter.increment();
        return result;
    }

    private static TriageResult parseTriageResult(String normalized, boolean threeWay) {
        if (!threeWay) {
            return normalized.contains("SPEAK") ? TriageResult.SPEAK_WITH_VISION : TriageResult.PASS;
        }
        if (normalized.contains("SPEAKTEXT") || normalized.contains("SPEAK_TEXT")) return TriageResult.SPEAK_TEXT_ONLY;
        if (normalized.contains("SPEAKVISION") || normalized.contains("SPEAK_VISION")) return TriageResult.SPEAK_WITH_VISION;
        if (normalized.contains("SPEAK")) return TriageResult.SPEAK_WITH_VISION;
        return TriageResult.PASS;
    }

    @Override
    public String generateComment(String userText, String base64JpegImage) {
        String input = (userText == null || userText.isBlank())
                ? "(자동 트리거. 한 마디 해 봐)"
                : userText;

        history.addUser(input);

        BotProperties.CodexCli cfg = properties.getCodexCli();
        String systemPrompt = buildSystemPrompt(cfg, input);

        Path imagePath = null;
        if (base64JpegImage != null && !base64JpegImage.isBlank()) {
            try {
                imagePath = writeTempImage(base64JpegImage, cfg.getTempImageDir());
                log.debug("임시 스크린샷 저장: {}", imagePath);
            } catch (Exception e) {
                log.warn("임시 스크린샷 저장 실패. 이미지 없이 진행: {}", e.getMessage());
            }
        }

        String prompt = buildPrompt(systemPrompt, imagePath != null);

        String raw;
        try {
            raw = callCodex(cfg, prompt, cfg.getModel(), imagePath, cfg.getTimeoutSec());
        } catch (Exception e) {
            log.error("Codex CLI 호출 실패", e);
            removeLastUserTurn();
            return PASS;
        } finally {
            deleteQuietly(imagePath);
        }

        String cleaned = raw == null ? "" : raw.trim();
        String sanitized = sanitizeOutput(cleaned);
        if (!sanitized.equals(cleaned)) {
            log.info("봇 raw 응답(sanitize 전): {}", cleaned);
            log.info("봇 응답(sanitize 후): {}", sanitized);
        } else {
            log.info("봇 raw 응답: {}", sanitized);
        }

        String normalized = sanitized.replaceAll("[.\"'\\s]", "").toUpperCase();
        if (normalized.equals("PASS") || normalized.isEmpty()) {
            removeLastUserTurn();
            passCounter.increment();
            return PASS;
        }

        history.addAssistant(sanitized);
        passCounter.reset();
        return sanitized;
    }

    @Override
    public String generateIdleComment(String idleSystemPrompt, String triggerText, String base64JpegImage) {
        String input = (triggerText == null || triggerText.isBlank())
                ? "(능동 트리거)"
                : triggerText;

        BotProperties.CodexCli cfg = properties.getCodexCli();

        // 캐릭터/히스토리/포켓몬/nudge/assertive 미적용. CLI 안정성용 extraSystemPrompt만 append.
        String systemPrompt = idleSystemPrompt;
        String extra = cfg.getExtraSystemPrompt();
        if (!isBlank(extra)) {
            systemPrompt = systemPrompt + "\n\n" + extra;
        }

        Path imagePath = null;
        if (base64JpegImage != null && !base64JpegImage.isBlank()) {
            try {
                imagePath = writeTempImage(base64JpegImage, cfg.getTempImageDir());
            } catch (Exception e) {
                log.warn("Idle용 임시 스크린샷 저장 실패. 이미지 없이 진행: {}", e.getMessage());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(systemPrompt).append("\n\n");
        if (imagePath != null) {
            sb.append("[현재 화면이 첨부됨] 화면 + 아래 트리거에 한 줄로 반응하라.\n\n");
        }
        sb.append("[능동 트리거]\n").append(input);
        String prompt = sb.toString();

        String raw;
        try {
            raw = callCodex(cfg, prompt, cfg.getModel(), imagePath, cfg.getTimeoutSec());
        } catch (Exception e) {
            log.error("Codex CLI idle 호출 실패", e);
            return PASS;
        } finally {
            deleteQuietly(imagePath);
        }

        String cleaned = raw == null ? "" : raw.trim();
        String sanitized = sanitizeOutput(cleaned);
        log.info("Idle 봇 응답: {}", sanitized);

        String normalized = sanitized.replaceAll("[.\"'\\s]", "").toUpperCase();
        if (normalized.equals("PASS") || normalized.isEmpty()) {
            return PASS;
        }
        return sanitized;
    }

    // ---------- 화면 번역 / 단발 vision/text 호출 ----------

    @Override
    public String analyzeImage(String systemPrompt, String userPrompt, String base64JpegImage) {
        return analyzeImage(systemPrompt, userPrompt, base64JpegImage, true);
    }

    @Override
    public String analyzeImage(String systemPrompt, String userPrompt,
                               String base64JpegImage, boolean useTriageModel) {
        BotProperties.CodexCli cfg = properties.getCodexCli();
        String model = pickModel(cfg, useTriageModel);
        Path imagePath = null;
        if (base64JpegImage != null && !base64JpegImage.isBlank()) {
            try {
                imagePath = writeTempImage(base64JpegImage, cfg.getTempImageDir());
            } catch (Exception e) {
                log.warn("analyzeImage 임시 스크린샷 저장 실패. 이미지 없이 진행: {}", e.getMessage());
            }
        }
        // 페르소나/히스토리 일체 미포함. systemPrompt+userPrompt 만.
        String prompt = "[지침]\n" + systemPrompt + "\n\n" + userPrompt;
        try {
            int timeout = useTriageModel ? cfg.getTriageTimeoutSec() : cfg.getTimeoutSec();
            return callCodex(cfg, prompt, model, imagePath, timeout);
        } catch (Exception e) {
            log.warn("analyzeImage Codex 호출 실패: {}", e.getMessage());
            throw new RuntimeException("analyzeImage 실패: " + e.getMessage(), e);
        } finally {
            deleteQuietly(imagePath);
        }
    }

    @Override
    public String analyzeText(String systemPrompt, String userPrompt, boolean useTriageModel) {
        BotProperties.CodexCli cfg = properties.getCodexCli();
        String model = pickModel(cfg, useTriageModel);
        String prompt = "[지침]\n" + systemPrompt + "\n\n" + userPrompt;
        try {
            int timeout = useTriageModel ? cfg.getTriageTimeoutSec() : cfg.getTimeoutSec();
            return callCodex(cfg, prompt, model, null, timeout);
        } catch (Exception e) {
            log.warn("analyzeText Codex 호출 실패: {}", e.getMessage());
            throw new RuntimeException("analyzeText 실패: " + e.getMessage(), e);
        }
    }

    private static String pickModel(BotProperties.CodexCli cfg, boolean useTriageModel) {
        if (useTriageModel) {
            return isBlank(cfg.getTriageModel()) ? cfg.getModel() : cfg.getTriageModel();
        }
        return cfg.getModel();
    }

    // ---------- 프롬프트 합성 ----------

    private String buildSystemPrompt(BotProperties.CodexCli cfg, String input) {
        String sys = character.getSystemPrompt();
        String pokemonCtx = pokemonContext.buildContext(input);
        if (pokemonCtx != null) {
            sys = sys + "\n\n" + pokemonCtx;
            log.debug("Pokemon 컨텍스트 주입됨");
        }
        if (cfg.isAssertive()) {
            sys = sys + ASSERTIVE_NUDGE;
        }
        String extra = cfg.getExtraSystemPrompt();
        if (!isBlank(extra)) {
            sys = sys + "\n\n" + extra;
        }
        sys = sys + passCounter.buildNudge("comment");
        return sys;
    }

    /**
     * 페르소나 + 히스토리 + 현재 발화를 한 프롬프트로 합성.
     * codex는 stateless 호출 + system/user 분리가 없으므로 매번 전체 컨텍스트를 본문에 넣는다.
     */
    private String buildPrompt(String systemPrompt, boolean hasImage) {
        StringBuilder sb = new StringBuilder();
        sb.append("[역할/지침 — 반드시 이 캐릭터로만 응답]\n")
          .append(systemPrompt)
          .append("\n\n");

        List<ConversationHistory.Turn> turns = history.snapshot();
        if (turns.size() > 1) {
            sb.append("[지금까지 대화]\n");
            for (int i = 0; i < turns.size() - 1; i++) {
                ConversationHistory.Turn turn = turns.get(i);
                String label = "user".equals(turn.role()) ? character.getStreamerName() : character.getName();
                sb.append(label).append(": ").append(turn.content()).append('\n');
            }
            sb.append('\n');
        }

        if (hasImage) {
            sb.append("[현재 화면이 첨부됨] 화면 + 아래 발화에 한 줄로 반응하라.\n\n");
        }

        ConversationHistory.Turn last = turns.get(turns.size() - 1);
        sb.append("[방금 들은 말]\n").append(last.content());
        return sb.toString();
    }

    private Path writeTempImage(String base64Jpeg, String dir) throws IOException {
        byte[] bytes = Base64.getDecoder().decode(base64Jpeg);
        Path baseDir = isBlank(dir)
                ? Paths.get(System.getProperty("java.io.tmpdir"))
                : Paths.get(dir);
        Files.createDirectories(baseDir);
        Path target = baseDir.resolve("reaction-bot-" + UUID.randomUUID() + ".jpg");
        Files.write(target, bytes);
        return target;
    }

    // ---------- CLI 호출 ----------

    /**
     * `codex exec` 호출. 프롬프트는 stdin(`-` sentinel)으로, 최종 메시지는 `-o <임시파일>`로 캡처.
     * @param modelOverride 빈 값이면 -m 생략 (codex 기본)
     * @param imagePath     null이 아니면 -i 로 첨부
     * @param timeoutSec    이 호출에 적용할 wait 최대 초
     */
    private String callCodex(BotProperties.CodexCli cfg, String prompt,
                             String modelOverride, Path imagePath, int timeoutSec) throws Exception {
        Path outFile = Files.createTempFile("codex-out-", ".txt");
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(cfg.getExecutable());
            cmd.add("exec");
            if (cfg.isSkipGitRepoCheck()) {
                cmd.add("--skip-git-repo-check");
            }
            if (cfg.isIgnoreUserConfig()) {
                cmd.add("--ignore-user-config");
            }
            if (!isBlank(modelOverride)) {
                cmd.add("-m");
                cmd.add(modelOverride);
            }
            if (!isBlank(cfg.getReasoningEffort())) {
                // codex -c 는 값 부분을 TOML로 파싱 → 문자열은 따옴표로 감싸야 함.
                cmd.add("-c");
                cmd.add("model_reasoning_effort=\"" + cfg.getReasoningEffort() + "\"");
            }
            if (imagePath != null) {
                cmd.add("-i");
                cmd.add(imagePath.toAbsolutePath().toString());
            }
            cmd.add("-o");
            cmd.add(outFile.toAbsolutePath().toString());
            // 프롬프트는 stdin으로 — 긴 페르소나/히스토리로 ARG_MAX 초과 방지.
            cmd.add("-");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (!isBlank(cfg.getWorkingDir())) {
                pb.directory(new File(cfg.getWorkingDir()));
            }
            // API 키가 설정돼 있으면 종량 인증으로 주입. 없으면 저장된 codex login(구독) 사용.
            if (!isBlank(cfg.getApiKey())) {
                pb.environment().put("CODEX_API_KEY", cfg.getApiKey());
            }
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.environment().put("LANG", "en_US.UTF-8");
            pb.redirectErrorStream(false);

            log.debug("Codex CLI 호출. argc={}, model={}, image={}, timeout={}s",
                    cmd.size(), modelOverride, imagePath != null, timeoutSec);

            long start = System.currentTimeMillis();
            Process proc = pb.start();

            try (OutputStream stdin = proc.getOutputStream()) {
                stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }

            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread tOut = drainAsync(proc.getInputStream(), out);
            Thread tErr = drainAsync(proc.getErrorStream(), err);

            boolean finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                tOut.join(500);
                tErr.join(500);
                throw new RuntimeException("Codex CLI timeout (" + timeoutSec + "s). stderr=" + err);
            }
            tOut.join(2000);
            tErr.join(500);

            long elapsed = System.currentTimeMillis() - start;
            int exit = proc.exitValue();
            log.debug("Codex CLI 종료. exit={}, {}ms, stdout {}자, stderr {}자",
                    exit, elapsed, out.length(), err.length());

            if (exit != 0) {
                throw new RuntimeException("Codex CLI exit=" + exit + " stderr=" + err);
            }

            // -o 파일에 최종 메시지가 기록됨. 비면 stdout으로 폴백.
            String fromFile = "";
            try {
                if (Files.size(outFile) > 0) {
                    fromFile = Files.readString(outFile, StandardCharsets.UTF_8);
                }
            } catch (IOException io) {
                log.debug("Codex -o 출력 파일 읽기 실패, stdout 폴백: {}", io.getMessage());
            }
            return fromFile.isBlank() ? out.toString() : fromFile;
        } finally {
            deleteQuietly(outFile);
        }
    }

    private Thread drainAsync(InputStream is, StringBuilder sink) {
        return Thread.startVirtualThread(() -> {
            byte[] buf = new byte[4096];
            try (is) {
                int n;
                while ((n = is.read(buf)) != -1) {
                    sink.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // 프로세스가 죽으면 read가 끊김. 정상.
            }
        });
    }

    // ---------- 출력 정제 (ClaudeCliService와 동일 컨셉) ----------

    private String sanitizeOutput(String s) {
        if (s == null || s.isEmpty()) return s;
        String out = s;
        for (int i = 0; i < 3; i++) {
            String next = LEADING_LABEL.matcher(out).replaceFirst("").trim();
            if (next.equals(out)) break;
            out = next;
        }
        out = WRAPPING_QUOTES.matcher(out).replaceAll("").trim();
        out = limitSentences(out, MAX_SENTENCES);
        return out;
    }

    private static String limitSentences(String s, int maxSentences) {
        if (s == null || s.isEmpty() || maxSentences <= 0) return s;
        int len = s.length();
        int sentences = 0;
        int i = 0;
        while (i < len) {
            if (isSentenceEnd(s.charAt(i))) {
                while (i + 1 < len && isSentenceEnd(s.charAt(i + 1))) i++;
                sentences++;
                if (sentences >= maxSentences) {
                    return s.substring(0, i + 1).trim();
                }
            }
            i++;
        }
        return s;
    }

    private static boolean isSentenceEnd(char c) {
        return c == '.' || c == '?' || c == '!' || c == '…'
                || c == '。' || c == '？' || c == '！';
    }

    private void removeLastUserTurn() {
        List<ConversationHistory.Turn> snapshot = history.snapshot();
        if (snapshot.isEmpty()) return;
        ConversationHistory.Turn last = snapshot.get(snapshot.size() - 1);
        if ("user".equals(last.role())) {
            history.popLast();
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException io) {
            log.warn("임시 파일 삭제 실패 ({}): {}", path, io.getMessage());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
