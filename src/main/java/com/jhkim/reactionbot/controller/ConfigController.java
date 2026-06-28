package com.jhkim.reactionbot.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhkim.reactionbot.config.BotProperties;
import com.jhkim.reactionbot.service.LlmOutputFilter;
import com.jhkim.reactionbot.service.UserConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigController {

    private final UserConfigService userConfig;
    private final LlmOutputFilter outputFilter;
    private final BotProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
     * 시스템에 잡혀있는 입력 장치(마이크) 목록 조회.
     * scripts/list_mic_devices.py를 띄워서 sounddevice 장치 목록을 JSON으로 받아온다.
     * 응답: { devices: [{index, name, channels, default}], current: 1|null }
     */
    private static final long MIC_PROBE_TIMEOUT_SECONDS = 10;

    @GetMapping("/mic-devices")
    public Map<String, Object> getMicDevices() {
        List<Map<String, Object>> devices = new ArrayList<>();
        try {
            BotProperties.Stt stt = properties.getStt();
            ProcessBuilder pb = new ProcessBuilder(
                    stt.getPythonExecutable(), "scripts/list_mic_devices.py");
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().reduce("", (a, b) -> a + b);
            }
            boolean finished = p.waitFor(MIC_PROBE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                log.warn("마이크 장치 목록 조회 타임아웃 ({}초)", MIC_PROBE_TIMEOUT_SECONDS);
            } else if (p.exitValue() != 0) {
                log.warn("마이크 장치 목록 조회 실패 (exit={}): {}", p.exitValue(), output);
            } else if (!output.isBlank()) {
                devices = objectMapper.readValue(output, List.class);
            }
        } catch (Exception e) {
            log.warn("마이크 장치 목록 조회 실패: {}", e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("devices", devices);
        out.put("current", properties.getStt().getDeviceIndex());
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

    /**
     * 서버 셀프 재기동 (Windows 한정 — 사용자가 start.bat / java -jar 로 띄운 환경).
     * 동작:
     *   1) ProcessHandle 로 현재 JVM 실행 명령을 얻는다.
     *   2) 임시 .bat 에 "2초 대기 → 같은 명령 재실행"을 적어 detached cmd 로 spawn.
     *   3) 응답이 클라이언트에 전달될 시간을 확보한 뒤 System.exit(0).
     *      Spring shutdown hook 이 도는 동안 새 인스턴스가 띄워진다.
     *
     * 자동 탐색이 실패하면 UI 에 안내 메시지를 띄우고 종료하지 않는다.
     */
    // 동일 재기동 동시 트리거(더블 클릭/재시도/병렬 요청) → 여러 인스턴스가 포트 8080 경쟁하는 사고 방지.
    private final java.util.concurrent.atomic.AtomicBoolean restartInProgress =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @PostMapping("/restart")
    public Map<String, Object> restart() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return Map.of(
                    "status", "error",
                    "message", "셀프 재기동은 현재 Windows 환경만 지원합니다.");
        }
        // 가드: spawn 까지 진행한 후엔 절대 다시 못 들어가게.
        if (!restartInProgress.compareAndSet(false, true)) {
            return Map.of(
                    "status", "ok",
                    "message", "이미 재기동이 진행 중입니다.");
        }
        boolean spawned = false;
        try {
            String cwd = System.getProperty("user.dir", ".");
            RelaunchPlan plan = resolveRelaunchPlan(cwd);
            if (plan == null) {
                return Map.of(
                        "status", "error",
                        "message", "재실행 명령을 결정할 수 없습니다. start.bat 없음 + ProcessHandle/JVM props 모두 비어있음. "
                                + "콘솔에서 수동으로 재실행해 주세요.");
            }
            // 명령 본문(JVM args/API 키 등 시크릿 포함 가능)은 로그에 남기지 않음. source 만 기록.
            log.info("재기동 명령 결정 (source={})", plan.source());

            Path script;
            try {
                script = Files.createTempFile("reaction-bot-restart-", ".bat");
                // 4초 대기 = 현재 JVM shutdown hook + 포트 8080 해제 여유.
                // 마지막 줄: 새 인스턴스 spawn 후 본인(.bat) 자가 삭제 → 시크릿이 디스크에 남지 않음.
                String body = "@echo off\r\n"
                        + "chcp 65001 >nul\r\n"
                        + "timeout /t 4 /nobreak >nul\r\n"
                        + "cd /d \"" + cwd + "\"\r\n"
                        + plan.command() + "\r\n"
                        + "del \"%~f0\" >nul 2>&1\r\n";
                Files.writeString(script, body, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.error("재기동 스크립트 생성 실패", e);
                return Map.of("status", "error", "message", "재기동 스크립트 생성 실패: " + e.getMessage());
            }

            try {
                new ProcessBuilder("cmd", "/c", "start", "Reaction Bot Restart", "cmd", "/c",
                        script.toAbsolutePath().toString())
                        .start();
                spawned = true;
            } catch (IOException e) {
                log.error("재기동 프로세스 spawn 실패", e);
                // spawn 실패 시 임시 스크립트 즉시 삭제(시크릿 보존 방지).
                try { Files.deleteIfExists(script); } catch (IOException ignored) {}
                return Map.of("status", "error", "message", "재기동 프로세스 spawn 실패: " + e.getMessage());
            }

            Thread killer = new Thread(() -> {
                try { Thread.sleep(800); } catch (InterruptedException ignored) {}
                log.info("System.exit(0) — 재기동 트리거");
                System.exit(0);
            }, "restart-killer");
            killer.setDaemon(true);
            killer.start();

            return Map.of(
                    "status", "ok",
                    "message", "재기동 트리거됨 (" + plan.source() + "). 약 5~10초 후 새 인스턴스가 뜹니다.");
        } finally {
            // spawn 성공 시엔 곧 System.exit(0) 이라 가드 해제할 필요 없음.
            // spawn 전에 return 한 경우(에러 등) 가드 풀어 다음 시도 가능하게.
            if (!spawned) restartInProgress.set(false);
        }
    }

    private record RelaunchPlan(String source, String command) {}

    /**
     * 재실행 명령을 결정. 우선순위:
     *   1) cwd/start.bat — 사용자 표준 진입점. 가장 안정적
     *   2) ProcessHandle.commandLine() — JDK/OS 에 따라 빈 값일 수 있음
     *   3) sun.java.command + RuntimeMXBean.getInputArguments() + java.home 으로 재조립
     */
    private RelaunchPlan resolveRelaunchPlan(String cwd) {
        Path startBat = Paths.get(cwd, "start.bat");
        if (Files.isRegularFile(startBat)) {
            return new RelaunchPlan("start.bat", "\"" + startBat.toAbsolutePath() + "\"");
        }

        Optional<String> handleCmd = ProcessHandle.current().info().commandLine();
        if (handleCmd.isPresent() && !handleCmd.get().isBlank()) {
            return new RelaunchPlan("ProcessHandle", handleCmd.get());
        }

        String javaCmd = System.getProperty("sun.java.command", "");
        if (javaCmd.isBlank()) {
            log.warn("재기동 폴백 실패: sun.java.command 도 비어있음");
            return null;
        }
        Path javaExe = Paths.get(System.getProperty("java.home", ""), "bin", "java.exe");
        if (!Files.isExecutable(javaExe)) {
            javaExe = Paths.get("java");
        }
        List<String> jvmArgs = java.lang.management.ManagementFactory
                .getRuntimeMXBean().getInputArguments();
        String[] parts = javaCmd.split("\\s+", 2);
        String first = parts[0];
        String rest = parts.length > 1 ? parts[1] : "";

        StringBuilder cmd = new StringBuilder();
        cmd.append(quoteIfNeeded(javaExe.toString()));
        for (String a : jvmArgs) {
            cmd.append(' ').append(quoteIfNeeded(a));
        }
        if (first.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            cmd.append(" -jar ").append(quoteIfNeeded(first));
        } else {
            String classpath = System.getProperty("java.class.path", "");
            if (!classpath.isBlank()) {
                cmd.append(" -cp ").append(quoteIfNeeded(classpath));
            }
            cmd.append(' ').append(first);
        }
        if (!rest.isBlank()) cmd.append(' ').append(rest);
        return new RelaunchPlan("reconstructed", cmd.toString());
    }

    private static String quoteIfNeeded(String s) {
        if (s == null || s.isEmpty()) return "\"\"";
        if (s.startsWith("\"") && s.endsWith("\"")) return s;
        if (s.contains(" ") || s.contains("\t")) return "\"" + s + "\"";
        return s;
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
