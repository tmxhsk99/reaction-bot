package com.jhkim.reactionbot.service;

import com.jhkim.reactionbot.config.BotProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
