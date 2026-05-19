package com.jhkim.reactionbot.service;

import com.jhkim.reactionbot.config.BotProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * STT 워커(scripts/stt_worker.py)를 자식 프로세스로 띄우고
 * 서버 종료 시 같이 죽인다.
 *
 * - ApplicationReadyEvent: 서버가 8080 떠서 워커가 POST할 수 있는 시점에 시작
 * - @PreDestroy: 서버 종료 시 프로세스 정리
 * - 워커 stdout은 [stt] 프리픽스로 로그에 흘림
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SttWorkerRunner {

    private final BotProperties properties;

    private Process process;
    private Thread logPumpThread;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        BotProperties.Stt stt = properties.getStt();
        if (!stt.isAutoStart()) {
            log.info("STT 워커 자동 시작 비활성화 (reaction-bot.stt.auto-start=false)");
            return;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(stt.getPythonExecutable());
        cmd.add(stt.getScriptPath());
        cmd.add("--server-url"); cmd.add(stt.getServerUrl());
        cmd.add("--model"); cmd.add(stt.getModel());
        cmd.add("--language"); cmd.add(stt.getLanguage());
        cmd.add("--compute-type"); cmd.add(stt.getComputeType());
        cmd.add("--device"); cmd.add(stt.getDevice());
        cmd.add("--vad-aggressiveness"); cmd.add(String.valueOf(stt.getVadAggressiveness()));
        if (stt.getDeviceIndex() != null) {
            cmd.add("--device-index"); cmd.add(String.valueOf(stt.getDeviceIndex()));
        }
        if (stt.getBeamSize() > 0) {
            cmd.add("--beam-size"); cmd.add(String.valueOf(stt.getBeamSize()));
        }
        if (stt.getInitialPrompt() != null && !stt.getInitialPrompt().isBlank()) {
            cmd.add("--initial-prompt"); cmd.add(stt.getInitialPrompt());
        }
        // 포켓몬 이름 사전을 임시 파일로 풀어서 Whisper initial_prompt에 자동 주입.
        // 배포 jar 안에 있어도 동작하도록 ClassPathResource 사용.
        Path pokemonPromptFile = extractClasspathToTemp(
                "pokemon-ko-en.json", "stt-prompt-pokemon-", ".json");
        if (pokemonPromptFile != null) {
            cmd.add("--prompt-file"); cmd.add(pokemonPromptFile.toString());
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("PYTHONUNBUFFERED", "1");

        try {
            log.info("STT 워커 시작: {}", String.join(" ", cmd));
            process = pb.start();
            logPumpThread = new Thread(this::pumpLogs, "stt-worker-log");
            logPumpThread.setDaemon(true);
            logPumpThread.start();
        } catch (IOException e) {
            log.error("STT 워커 시작 실패. python 실행기({})와 스크립트 경로({})를 확인하세요.",
                    stt.getPythonExecutable(), stt.getScriptPath(), e);
        }
    }

    /**
     * 클래스패스 리소스를 임시 파일로 복사해서 경로를 반환. JVM 종료 시 자동 삭제.
     * 배포 jar 안의 리소스를 외부 프로세스(파이썬)에 파일 경로로 전달하기 위함.
     */
    private Path extractClasspathToTemp(String resourceName, String prefix, String suffix) {
        try {
            ClassPathResource res = new ClassPathResource(resourceName);
            if (!res.exists()) {
                log.warn("클래스패스 리소스 없음 (건너뜀): {}", resourceName);
                return null;
            }
            Path temp = Files.createTempFile(prefix, suffix);
            try (InputStream in = res.getInputStream()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            temp.toFile().deleteOnExit();
            return temp;
        } catch (IOException e) {
            log.warn("클래스패스 리소스 추출 실패 ({}): {}", resourceName, e.getMessage());
            return null;
        }
    }

    private void pumpLogs() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[stt] {}", line);
            }
        } catch (IOException e) {
            log.debug("STT 워커 로그 파이프 종료: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        if (process == null || !process.isAlive()) {
            return;
        }
        log.info("STT 워커 종료 중...");
        process.destroy();
        try {
            if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("STT 워커가 3초 내 종료 안 됨. 강제 종료.");
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        log.info("STT 워커 종료 완료.");
    }
}
