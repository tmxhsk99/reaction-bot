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
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Claude Code CLI 백엔드. 봇이 subprocess로 `claude` CLI를 호출해 응답을 받는다.
 *
 * 동기:
 *   API 결제(토큰 단가) 대신 Pro/Max 구독 한도 안에서 처리 → 호출 비용 0.
 *
 * 동작:
 *   1) base64 JPEG → 임시 파일에 디코드 저장
 *   2) ProcessBuilder로 `claude --print --system-prompt ... [--model ...]` 기동
 *      → --append가 아닌 --system-prompt 사용: Claude Code의 기본 "소프트웨어 개발 어시스턴트"
 *        시스템 프롬프트를 통째로 교체. 안 그러면 캐릭터 지침이 default에 밀려 봇이 자기 정체성
 *        ("저는 Claude Code입니다...")을 드러내고 RP를 거부함.
 *   3) 프롬프트(히스토리 + 발화 + 이미지 경로 안내)는 stdin으로 전달
 *      → Claude Code가 프롬프트 안의 절대경로를 Read 도구로 자동 로드
 *   4) stdout 캡처 → sanitize → 히스토리 저장 → orchestrator가 TTS
 *   5) 임시 이미지는 finally에서 항상 삭제
 *
 * 특성:
 *   - subprocess 오버헤드(콜드 스타트 수 초)가 크므로 hasSeparateTriage=false로 1회 호출
 *   - acceptsImage=true. 화면 캡처는 orchestrator가 담당
 *   - 출력 정제는 ollama 서비스와 동일 로직(라벨 prefix·따옴표·문장 수 컷)
 *
 * reaction-bot.llm.provider=claude-cli 일 때만 빈 생성.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "reaction-bot.llm", name = "provider", havingValue = "claude-cli")
public class ClaudeCliService implements LlmProvider {

    private static final Pattern LEADING_LABEL =
            Pattern.compile("^\\s*(?:[\\[\\(]\\s*[^\\]\\)\\r\\n]{1,20}\\s*[\\]\\)]|\\*{1,2}[^*\\r\\n]{1,20}\\*{1,2}|[\\p{L}]{1,10})\\s*[:：]\\s*");
    private static final Pattern WRAPPING_QUOTES =
            Pattern.compile("^[\"'“”‘’`]+|[\"'“”‘’`]+$");

    // MAX_SENTENCES 는 properties.getClaudeCli().getMaxSentences() 로 이동
    // (claude-cli.yml 에서 0 으로 두면 무제한 = raw 그대로 통과).

    /** ollama 서비스와 동일 컨셉. 구독 한도 안이라 비용 무관 → 적극성 부스트. */
    private static final String ASSERTIVE_NUDGE = """


            [Claude CLI 모드 추가 지침]
            - 평소보다 한 발 더 적극적으로 끼어들어라.
            - "PASS 3가지 조건"이 명확히 충족될 때만 PASS. 애매하면 한 마디.
            - 매번 다른 각도(놀림/응원/예측/딴지/자랑)로 변주.
            """;

    /** triage-enabled=true일 때 사용. ClaudeService/GeminiService와 동일 정책 (라벨 단답). */
    private static final String TRIAGE_SYSTEM = """
            당신은 스트리밍 코멘터리 봇의 판단기입니다.
            방금 스트리머가 한 말(텍스트)만 보고, 봇이 "지금 한 마디 해도 될 상황인가" 판단하세요.
            (비용 절감을 위해 1차 판단에는 화면 이미지가 제공되지 않습니다. 텍스트만으로 보수적으로 판단.)
            답은 정확히 SPEAK 또는 PASS 둘 중 하나. 다른 글자 절대 금지.
            기본 자세: 적극적으로 끼어든다. 웬만하면 SPEAK, 애매하면 SPEAK.

            SPEAK 기준 (대부분 SPEAK):
              - 게임/상황에 대한 발화, 감정 표현, 반응 유도
              - 스트리머가 뭔가에 반응하거나 말을 걸 때
              - 한참 조용했다가 나온 발화
              - 어느 쪽인지 애매하면 SPEAK

            PASS 기준 (명백할 때만):
              - 시청자에게 안내/설명하는 중
              - 게임 NPC/캐릭터 대사를 그대로 따라 읽는 중
              - 의미 없는 추임새·헛기침뿐
            """;

    /** multimodal-mode=ai-decide 전용. SPEAK를 vision 필요 여부로 둘로 쪼갬. */
    private static final String TRIAGE_SYSTEM_WITH_VISION = """
            당신은 스트리밍 코멘터리 봇의 판단기입니다.
            방금 스트리머가 한 말(텍스트)만 보고 다음 3가지 중 하나를 선택하세요.
            답은 정확히 다음 라벨 셋 중 하나. 다른 글자 절대 금지: PASS | SPEAK_VISION | SPEAK_TEXT
            기본 자세: 적극적으로 끼어든다. 애매하면 PASS하지 말고 SPEAK_VISION/SPEAK_TEXT 중 하나를 골라라.

            PASS (명백할 때만):
              - 시청자에게 안내/설명, NPC 대사를 그대로 따라 읽는 중, 의미 없는 추임새뿐

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

    /**
     * @PostConstruct 에서 한 번 resolve. Claude Code 버전 업데이트는 봇 재시작 시 반영.
     * (방송 중 자동 업데이트 따라가지 않아도 OK — 콜드 스타트 절약이 더 큼)
     */
    private String resolvedExecutable;

    /**
     * 인증 풀림 감지 시 사용자에게 새 콘솔을 띄워 인터랙티브 로그인을 시켰는지 여부.
     * 한 번 띄우면 봇 세션 동안 다시 안 띄움 — 연속 호출 실패마다 콘솔 창 폭주 방지.
     * 봇 재시작 또는 첫 성공 호출에서 reset.
     */
    private final AtomicBoolean interactiveLoginShown = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        BotProperties.ClaudeCli cfg = properties.getClaudeCli();
        this.resolvedExecutable = resolveExecutable(cfg);
        log.info("LLM provider=claude-cli. exec={} (cfg.executable={}, model={}, workingDir={}, stdin={}, timeout={}s)",
                resolvedExecutable,
                cfg.getExecutable(),
                isBlank(cfg.getModel()) ? "(CLI 기본)" : cfg.getModel(),
                isBlank(cfg.getWorkingDir()) ? "(봇 cwd)" : cfg.getWorkingDir(),
                cfg.isUseStdinPrompt(),
                cfg.getTimeoutSec());
        // claude-cli 모드는 Pro/Max 구독 인증 사용이 목적. 환경에 ANTHROPIC_API_KEY 가 있으면
        // CLI 가 그걸 우선시해 종량 과금이 발생할 수 있어, 자식 프로세스 환경에서 제거할 예정임을 안내.
        String envKey = System.getenv("ANTHROPIC_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            log.info("claude-cli 모드: ANTHROPIC_API_KEY 환경변수가 감지됨 → CLI subprocess 에서 제거하여 "
                   + "구독 인증(claude login)을 강제 사용합니다.");
        }
    }

    /**
     * 실행파일 결정 우선순위:
     *  1) executable-search-dir 가 명시되었으면 그 한 곳만 시도.
     *  2) 아니면 OS별 기본 후보 디렉토리들을 순서대로 시도 — Windows의 경우:
     *     a) %APPDATA%\Claude\claude-code  (네이티브 인스톨러 위치)
     *     b) %LOCALAPPDATA%\Packages\Claude_*\LocalCache\Roaming\Claude\claude-code  (MSIX 가상화 실 경로)
     *  3) 어느 후보 디렉토리에서 가장 높은 SemVer 폴더의 claude.exe(또는 claude)를 찾으면 그걸 사용.
     *  4) 모두 실패하면 cfg.executable 을 그대로 사용 (PATH 또는 사용자가 박아둔 절대경로).
     * (b)가 필요한 이유: MSIX 패키지로 깔린 Claude Desktop은 %APPDATA% 경로를 AppContainer
     * redirect로 가상화함. PowerShell/Explorer는 따라가지만 Java NIO Files.isDirectory()는
     * 가상화 레이어를 우회해 false를 반환 → 실 경로를 직접 시도해야 함.
     */
    private String resolveExecutable(BotProperties.ClaudeCli cfg) {
        List<String> searchDirs = new ArrayList<>();
        String userSearchDir = cfg.getExecutableSearchDir();
        if (!isBlank(userSearchDir)) {
            searchDirs.add(userSearchDir);
        } else {
            searchDirs.addAll(defaultSearchDirs());
        }
        for (String searchDir : searchDirs) {
            Path dir = Paths.get(searchDir);
            if (!Files.isDirectory(dir)) {
                log.debug("Claude CLI search-dir 없음: {}", searchDir);
                continue;
            }
            Path latest = findLatestClaude(dir);
            if (latest != null) {
                log.info("Claude CLI 자동 탐색: {} → {}", searchDir, latest);
                return latest.toString();
            }
            log.warn("Claude CLI 자동 탐색 실패 (search-dir={})", searchDir);
        }
        log.warn("Claude CLI 자동 탐색 실패 — fallback exec='{}'", cfg.getExecutable());
        return cfg.getExecutable();
    }

    /** Windows에서만 자동 추정. 다른 OS는 빈 리스트(=사용자가 명시해야 자동 탐색 가동). */
    private static List<String> defaultSearchDirs() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) return List.of();
        List<String> candidates = new ArrayList<>();
        // MSIX 실 경로(%LOCALAPPDATA%\Packages\Claude_*\...)를 가상 경로보다 먼저.
        // 둘 다 ProcessBuilder 로는 실행되지만, 에러 메시지에 가상 경로가 박히면 사용자가
        // PowerShell에서 그대로 복붙했을 때 CommandNotFoundException(MSIX redirect)으로 실패함.
        // 자동 탐색이 실 경로를 잡아야 안내 메시지가 PowerShell 에서도 그대로 실행 가능.
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            Path packagesDir = Paths.get(localAppData, "Packages");
            if (Files.isDirectory(packagesDir)) {
                try (Stream<Path> stream = Files.list(packagesDir)) {
                    stream.filter(Files::isDirectory)
                            .filter(p -> p.getFileName().toString().startsWith("Claude_"))
                            .map(p -> p.resolve("LocalCache")
                                    .resolve("Roaming")
                                    .resolve("Claude")
                                    .resolve("claude-code"))
                            .filter(Files::isDirectory)
                            .forEach(p -> candidates.add(p.toString()));
                } catch (IOException e) {
                    log.debug("MSIX Packages 디렉토리 스캔 실패: {}", e.getMessage());
                }
            }
        }
        // %APPDATA% 가상 경로 — MSIX 패키지가 아닌 네이티브 설치 본 케이스 대응 fallback.
        String appdata = System.getenv("APPDATA");
        if (appdata != null && !appdata.isBlank()) {
            candidates.add(appdata + File.separator + "Claude" + File.separator + "claude-code");
        }
        return candidates;
    }

    /**
     * 사용자가 PowerShell 에 그대로 복붙해 실행할 수 있는 경로 산출.
     * {@link #resolvedExecutable} 이 MSIX 가상 경로(`%APPDATA%\Claude\...`)면
     * PowerShell `&` 호출이 CommandNotFoundException 으로 거부한다. 실 경로
     * (`%LOCALAPPDATA%\Packages\Claude_*\LocalCache\Roaming\Claude\claude-code\...`)로
     * 변환 시도하고, 변환 불가하거나 이미 실 경로면 그대로 반환.
     * 인증 에러 안내 메시지에서만 사용 — ProcessBuilder 호출엔 영향 없음.
     */
    private String userFacingExecutable() {
        String exec = resolvedExecutable;
        if (exec == null || exec.isBlank()) return exec;
        String appdata = System.getenv("APPDATA");
        if (appdata == null || appdata.isBlank()) return exec;
        String virtualPrefix = appdata + File.separator + "Claude" + File.separator + "claude-code";
        if (!exec.startsWith(virtualPrefix)) return exec; // 이미 실 경로거나 사용자 지정 경로
        String tail = exec.substring(virtualPrefix.length()); // \2.1.165\claude.exe
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) return exec;
        Path packagesDir = Paths.get(localAppData, "Packages");
        if (!Files.isDirectory(packagesDir)) return exec;
        try (Stream<Path> stream = Files.list(packagesDir)) {
            return stream.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("Claude_"))
                    .map(p -> p.resolve("LocalCache").resolve("Roaming").resolve("Claude").resolve("claude-code"))
                    .filter(Files::isDirectory)
                    .map(claudeCodeDir -> {
                        // 1순위: 가상 경로와 동일 버전 (MSIX redirect 라 보통 일치)
                        Path sameVersion = Paths.get(claudeCodeDir.toString() + tail);
                        if (Files.isRegularFile(sameVersion)) return sameVersion.toString();
                        // 2순위: 실 경로 디렉토리에서 latest claude.exe 재탐색
                        Path latest = findLatestClaude(claudeCodeDir);
                        return latest == null ? null : latest.toString();
                    })
                    .filter(s -> s != null)
                    .findFirst()
                    .orElse(exec);
        } catch (IOException e) {
            return exec;
        }
    }

    /**
     * 인증 풀림 감지 시 사용자에게 새 콘솔 창을 띄워 인터랙티브 로그인 모드 실행.
     * 배포판 사용자는 봇 콘솔 로그를 안 보기 때문에, "갑자기 봇이 안 응답"하는 상황에서
     * 새 창이 자동으로 떠야 인지/조치가 가능. cmd /c start 로 분리 창 spawn → 봇 종료해도
     * 살아있음. 사용자는 그 창에서 미인증 시 자동으로 뜨는 브라우저 인증을 완료하면 끝.
     *
     * 한 번 띄우면 {@link #interactiveLoginShown} 으로 중복 차단 (연속 실패 호출마다
     * 창 폭주 방지). 봇 재시작 또는 다음 성공 호출에서 reset.
     */
    private void tryOpenInteractiveLogin() {
        if (!interactiveLoginShown.compareAndSet(false, true)) return;
        String os = System.getProperty("os.name", "").toLowerCase();
        String exec = userFacingExecutable();
        if (!os.contains("win")) {
            log.error("로그인 콘솔 자동 띄우기는 Windows 전용. 수동으로 실행해주세요: {}", exec);
            return;
        }
        try {
            // cmd /c start "<title>" cmd /k "<claude.exe>"
            //  - start: 봇 프로세스에서 분리된 새 콘솔 윈도우 spawn
            //  - 첫 "" 인자: start 가 그걸 타이틀로 인식 (없으면 다음 인자가 타이틀로 먹힘)
            //  - cmd /k: claude.exe 종료 후에도 콘솔 유지 → 사용자가 메시지 확인 가능
            new ProcessBuilder("cmd", "/c", "start", "Claude Code 로그인 필요", "cmd", "/k", exec)
                    .inheritIO()
                    .start();
            log.error("Claude CLI 인증이 풀려 새 콘솔 창에 로그인 모드를 띄웠습니다. "
                    + "그 창에서 인증을 완료하면 봇이 자동 복구됩니다. exec={}", exec);
        } catch (IOException e) {
            log.error("로그인 콘솔 자동 띄우기 실패. 수동으로 실행해주세요: {}", exec, e);
        }
    }

    /**
     * dir 직속 하위 폴더 중 SemVer로 가장 높은 폴더의 실행파일을 찾는다.
     * 폴더명은 보통 "2.1.149" 형식. 숫자가 아닌 부분은 0으로 처리.
     */
    private static Path findLatestClaude(Path dir) {
        String os = System.getProperty("os.name", "").toLowerCase();
        String exeName = os.contains("win") ? "claude.exe" : "claude";
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(p -> p.resolve(exeName))
                    .filter(Files::isRegularFile)
                    .max(Comparator.comparing(
                            p -> parseVersion(p.getParent().getFileName().toString()),
                            VERSION_COMPARATOR))
                    .orElse(null);
        } catch (IOException e) {
            log.warn("Claude CLI search-dir 읽기 실패 ({}): {}", dir, e.getMessage());
            return null;
        }
    }

    /** "2.1.149" → [2,1,149]. 비숫자는 0으로. 짧으면 뒤를 0으로 패딩. */
    private static int[] parseVersion(String s) {
        String[] parts = s.split("\\.");
        int[] result = new int[Math.max(parts.length, 3)];
        for (int i = 0; i < parts.length; i++) {
            String digits = parts[i].replaceAll("[^0-9]", "");
            try {
                result[i] = digits.isEmpty() ? 0 : Integer.parseInt(digits);
            } catch (NumberFormatException e) {
                result[i] = 0;
            }
        }
        return result;
    }

    private static final Comparator<int[]> VERSION_COMPARATOR = (a, b) -> {
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int av = i < a.length ? a[i] : 0;
            int bv = i < b.length ? b[i] : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    };

    /**
     * triage-enabled 설정에 따라 동적. true면 orchestrator가 2단계 호출 (triage CLI → main CLI).
     * subprocess 콜드스타트가 2번 도는 비용을 감수하고 캡처/Read tool 호출/큰 모델 추론을
     * 조건부로 건너뛸 가치가 있을 때만 ON.
     */
    @Override
    public boolean hasSeparateTriage() {
        return properties.getClaudeCli().isTriageEnabled();
    }

    @Override
    public boolean acceptsImage() {
        return true;
    }

    /** PASS/SPEAK 1차 판단을 별도 CLI 호출로 수행. triage-enabled=false면 호출되지 않음. */
    @Override
    public TriageResult triage(String userText, boolean needsVisionDecision) {
        String input = (userText == null || userText.isBlank()) ? "(자동 트리거)" : userText;
        BotProperties.ClaudeCli cfg = properties.getClaudeCli();

        String basePrompt = character.substitute(needsVisionDecision ? TRIAGE_SYSTEM_WITH_VISION : TRIAGE_SYSTEM);
        // triage는 캐릭터/극중 모드 무관. 라벨 단답만. nudge는 PASS 누적 시 자동 합성.
        String systemPrompt = basePrompt + passCounter.buildNudge("triage");

        // triage 모델은 cfg.triageModel 우선, 비어있으면 main model.
        String model = isBlank(cfg.getTriageModel()) ? cfg.getModel() : cfg.getTriageModel();

        String raw;
        try {
            // image 안 쓰므로 allowedTools 비움. timeout 짧게.
            raw = callCliCustom(cfg, systemPrompt, input, model, "", cfg.getTriageTimeoutSec());
        } catch (Exception e) {
            // triage 실패 시 안전하게 SPEAK_WITH_VISION (본 호출로 진행, 기존 동작과 유사).
            log.warn("Triage CLI 호출 실패. SPEAK_WITH_VISION로 폴백: {}", e.getMessage());
            return TriageResult.SPEAK_WITH_VISION;
        }

        String normalized = raw.toUpperCase().replaceAll("[^A-Z_]", "");
        TriageResult result = parseTriageResult(normalized, needsVisionDecision);
        log.info("Triage 결과 (CLI): {} (raw='{}', 연속PASS={})", result, normalized, passCounter.get());

        if (result == TriageResult.PASS) passCounter.increment();
        return result;
    }

    /** ClaudeService와 동일. SPEAK 라벨 변형에 best-effort 매칭. */
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

        BotProperties.ClaudeCli cfg = properties.getClaudeCli();
        String systemPrompt = buildSystemPrompt(cfg, input);

        // base64 JPEG → 임시 파일
        Path imagePath = null;
        if (base64JpegImage != null && !base64JpegImage.isBlank()) {
            try {
                imagePath = writeTempImage(base64JpegImage, cfg.getTempImageDir());
                log.debug("임시 스크린샷 저장: {}", imagePath);
            } catch (Exception e) {
                log.warn("임시 스크린샷 저장 실패. 이미지 없이 진행: {}", e.getMessage());
            }
        }

        String userPrompt = buildUserPrompt(imagePath);

        String raw;
        try {
            raw = callCli(cfg, systemPrompt, userPrompt);
        } catch (Exception e) {
            log.error("Claude CLI 호출 실패", e);
            removeLastUserTurn();
            return PASS;
        } finally {
            if (imagePath != null) {
                try {
                    Files.deleteIfExists(imagePath);
                } catch (IOException io) {
                    log.warn("임시 스크린샷 삭제 실패 ({}): {}", imagePath, io.getMessage());
                }
            }
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

        BotProperties.ClaudeCli cfg = properties.getClaudeCli();

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

        // 히스토리 없는 단발 프롬프트로 직접 합성.
        StringBuilder sb = new StringBuilder();
        if (imagePath != null) {
            sb.append("[현재 화면 스크린샷]\n")
              .append(imagePath.toAbsolutePath())
              .append("\n위 경로의 이미지를 Read로 한 번만 확인하고, 화면 + 아래 트리거에 한 줄로 반응하라.\n\n");
        }
        sb.append("[능동 트리거]\n").append(input);
        String userPrompt = sb.toString();

        String raw;
        try {
            raw = callCli(cfg, systemPrompt, userPrompt);
        } catch (Exception e) {
            log.error("Claude CLI idle 호출 실패", e);
            return PASS;
        } finally {
            if (imagePath != null) {
                try {
                    Files.deleteIfExists(imagePath);
                } catch (IOException io) {
                    log.warn("Idle용 임시 스크린샷 삭제 실패 ({}): {}", imagePath, io.getMessage());
                }
            }
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

    /**
     * 페르소나·히스토리 거치지 않는 raw vision 호출 (포켓몬 오버레이 등 부가 기능용).
     * triageModel 우선, 비어있으면 main model. allowedTools="Read"로 임시 이미지 읽기만 허용.
     * 응답은 그대로 반환 (sanitize·문장수 제한 없음 — JSON 구조 깨질 우려).
     */
    @Override
    public String analyzeImage(String systemPrompt, String userPrompt, String base64JpegImage) {
        BotProperties.ClaudeCli cfg = properties.getClaudeCli();
        String model = isBlank(cfg.getTriageModel()) ? cfg.getModel() : cfg.getTriageModel();

        Path imagePath = null;
        if (base64JpegImage != null && !base64JpegImage.isBlank()) {
            try {
                imagePath = writeTempImage(base64JpegImage, cfg.getTempImageDir());
            } catch (Exception e) {
                log.warn("analyzeImage 임시 스크린샷 저장 실패. 이미지 없이 진행: {}", e.getMessage());
            }
        }

        StringBuilder sb = new StringBuilder();
        if (imagePath != null) {
            sb.append("[분석 대상 스크린샷]\n")
              .append(imagePath.toAbsolutePath())
              .append("\n위 경로의 이미지를 Read 도구로 한 번만 확인하고 아래 지시에 따라 응답하라.\n\n");
        }
        sb.append(userPrompt);
        String composedUserPrompt = sb.toString();

        try {
            // 본 호출과 동일한 timeout 사용. JSON 추출이 느릴 수 있어 triageTimeout보단 본 timeout이 안전.
            return callCliCustom(cfg, systemPrompt, composedUserPrompt, model, "Read", cfg.getTimeoutSec());
        } catch (Exception e) {
            log.warn("analyzeImage CLI 호출 실패: {}", e.getMessage());
            throw new RuntimeException("analyzeImage 실패: " + e.getMessage(), e);
        } finally {
            if (imagePath != null) {
                try {
                    Files.deleteIfExists(imagePath);
                } catch (IOException io) {
                    log.warn("analyzeImage 임시 스크린샷 삭제 실패 ({}): {}", imagePath, io.getMessage());
                }
            }
        }
    }

    // ---------- 프롬프트 합성 ----------

    private String buildSystemPrompt(BotProperties.ClaudeCli cfg, String input) {
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
     * 히스토리(현재 입력 포함)와 이미지 경로 안내를 한 프롬프트로 합성.
     * Claude Code CLI는 stateless 호출이라 매번 전체 컨텍스트를 새로 넘긴다.
     */
    private String buildUserPrompt(Path imagePath) {
        StringBuilder sb = new StringBuilder();
        List<ConversationHistory.Turn> turns = history.snapshot();

        // 마지막 turn은 방금 add한 현재 입력. 그 이전이 과거 대화.
        if (turns.size() > 1) {
            sb.append("[지금까지 대화]\n");
            for (int i = 0; i < turns.size() - 1; i++) {
                ConversationHistory.Turn turn = turns.get(i);
                String label = "user".equals(turn.role()) ? character.getStreamerName() : character.getName();
                sb.append(label).append(": ").append(turn.content()).append('\n');
            }
            sb.append('\n');
        }

        if (imagePath != null) {
            sb.append("[현재 화면 스크린샷]\n")
              .append(imagePath.toAbsolutePath())
              .append("\n위 경로의 이미지를 Read로 한 번만 확인하고, 화면 + 아래 발화에 한 줄로 반응하라.\n\n");
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

    /** 본 호출용. cfg 기본값(model/allowedTools/timeoutSec) 사용. */
    private String callCli(BotProperties.ClaudeCli cfg, String systemPrompt, String userPrompt) throws Exception {
        return callCliCustom(cfg, systemPrompt, userPrompt, cfg.getModel(), cfg.getAllowedTools(), cfg.getTimeoutSec());
    }

    /**
     * 모델/도구/타임아웃을 명시적으로 줄 수 있는 변형. triage 호출 같이 다른 모델·짧은 타임아웃이 필요할 때 사용.
     * @param modelOverride    빈 값이면 --model 생략 (CLI 기본)
     * @param allowedToolsOverride 빈 값이면 --allowedTools 생략 (예: triage엔 Read 불필요)
     * @param timeoutSec       이 호출에 적용할 wait 최대 초
     */
    private String callCliCustom(BotProperties.ClaudeCli cfg, String systemPrompt, String userPrompt,
                                 String modelOverride, String allowedToolsOverride, int timeoutSec) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolvedExecutable);
        cmd.add("--print");                                  // non-interactive
        // --system-prompt: Claude Code 기본 시스템 프롬프트를 통째로 교체.
        // --append 쓰면 "당신은 Claude Code, 코딩 어시스턴트" 가 default로 박혀있어
        // 캐릭터 지침을 무시하고 "혼동이 생겼습니다. 저는 Claude Code입니다..." 식으로 RP 거부함.
        cmd.add("--system-prompt");
        cmd.add(systemPrompt);
        if (!isBlank(modelOverride)) {
            cmd.add("--model");
            cmd.add(modelOverride);
        }
        if (!isBlank(allowedToolsOverride)) {
            cmd.add("--allowedTools");
            cmd.add(allowedToolsOverride);
        }
        if (!cfg.isUseStdinPrompt()) {
            cmd.add(userPrompt);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (!isBlank(cfg.getWorkingDir())) {
            pb.directory(new File(cfg.getWorkingDir()));
        }
        // Node 계열 도구가 UTF-8 콘솔 출력을 안정적으로 내도록 강제.
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("LANG", "en_US.UTF-8");
        // claude-cli 모드는 Pro/Max 구독 인증 전용. 환경에 ANTHROPIC_API_KEY 가 박혀있으면
        // CLI 가 그걸 우선시해 종량 과금이 발생 → 자식 프로세스 환경에서 명시적으로 제거.
        // (호출자의 셸 환경은 그대로 두고, 봇이 만든 subprocess 환경에서만 unset.)
        pb.environment().remove("ANTHROPIC_API_KEY");
        pb.environment().remove("ANTHROPIC_AUTH_TOKEN");
        pb.redirectErrorStream(false);

        log.debug("Claude CLI 호출. argc={}, stdin={}, model={}, allowedTools={}, timeout={}s",
                cmd.size(), cfg.isUseStdinPrompt(), modelOverride, allowedToolsOverride, timeoutSec);

        long start = System.currentTimeMillis();
        Process proc = pb.start();

        // stdin 으로 프롬프트 전달 (길이 안전)
        if (cfg.isUseStdinPrompt()) {
            try (OutputStream stdin = proc.getOutputStream()) {
                stdin.write(userPrompt.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }
        }

        // 큰 출력 buffer 막힘 방지 — 별도 가상스레드로 stdout/stderr 동시 드레인.
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread tOut = drainAsync(proc.getInputStream(), out);
        Thread tErr = drainAsync(proc.getErrorStream(), err);

        boolean finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            // 드레이너 잠깐 기다렸다가 부분 출력이라도 로깅
            tOut.join(500);
            tErr.join(500);
            throw new RuntimeException("Claude CLI timeout (" + timeoutSec + "s). stderr=" + err);
        }
        tOut.join(2000);
        tErr.join(500);

        long elapsed = System.currentTimeMillis() - start;
        int exit = proc.exitValue();
        log.debug("Claude CLI 종료. exit={}, {}ms, stdout {}자, stderr {}자",
                exit, elapsed, out.length(), err.length());

        if (exit != 0) {
            // Claude Code CLI 는 인증 에러("Not logged in · Please run /login")를 stderr 가 아닌
            // stdout 에 적고 exit=1 로 종료한다. stderr 만 보면 빈 문자열이라 원인을 못 잡으므로
            // 둘 다 합쳐서 인증 에러 패턴을 우선 감지하고, 일반 에러는 stdout/stderr 를 같이 노출.
            String stdoutStr = out.toString().trim();
            String stderrStr = err.toString().trim();
            String combined = (stdoutStr + " " + stderrStr).toLowerCase();
            if (combined.contains("not logged in") || combined.contains("please run /login")) {
                // 배포판 사용자(콘솔 로그 안 보는 케이스) 대응: 봇이 직접 새 콘솔 창을 띄워
                // 인터랙티브 로그인 모드를 시작. 사용자는 그 창에서 자동으로 뜨는 브라우저 인증만
                // 끝내면 됨 → 봇은 다음 호출부터 자동 복구.
                tryOpenInteractiveLogin();
                throw new RuntimeException(
                        "Claude CLI 로그인 세션이 풀렸습니다. 새 콘솔 창에 로그인 모드를 띄웠으니 "
                                + "그 창에서 인증을 완료해주세요. (창이 안 떴다면 수동: & \""
                                + userFacingExecutable() + "\")");
            }
            throw new RuntimeException("Claude CLI exit=" + exit + " stdout=" + stdoutStr + " stderr=" + stderrStr);
        }
        if (err.length() > 0) {
            log.debug("Claude CLI stderr: {}", err);
        }
        // 성공 호출 = 로그인 살아있음. 다음에 또 풀리면 새 콘솔 띄울 수 있도록 latch reset.
        interactiveLoginShown.set(false);
        return out.toString();
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

    // ---------- 출력 정제 (ollama 서비스와 동일 컨셉) ----------

    private String sanitizeOutput(String s) {
        if (s == null || s.isEmpty()) return s;
        String out = s;
        for (int i = 0; i < 3; i++) {
            String next = LEADING_LABEL.matcher(out).replaceFirst("").trim();
            if (next.equals(out)) break;
            out = next;
        }
        out = WRAPPING_QUOTES.matcher(out).replaceAll("").trim();
        // 설정값 ≤ 0 이면 문장 수 제한 끔(raw 그대로). > 0 이면 해당 문장 수까지만 유지.
        int max = properties.getClaudeCli().getMaxSentences();
        if (max > 0) {
            out = limitSentences(out, max);
        }
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

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
