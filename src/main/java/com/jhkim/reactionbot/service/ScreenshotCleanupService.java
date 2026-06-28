package com.jhkim.reactionbot.service;

import com.jhkim.reactionbot.config.BotProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Claude CLI / Codex CLI 가 LLM 호출 시 만든 임시 스크린샷 파일 정리.
 *
 * 정상 흐름에서는 각 호출의 finally 블록에서 deleteIfExists 로 즉시 삭제되지만,
 * JVM 크래시 / 종료 타임아웃 시 잔재 가능 → 기동/종료 시 일괄 스윕.
 *
 * 화면 번역 모드는 1초 주기로 수십~수백 개를 만들었다 지우므로 잔재 가능성이 더 높다.
 *
 * 대상: {tmpdir}/reaction-bot-*.jpg
 * 검사 디렉토리: java.io.tmpdir + claudeCli.tempImageDir + codexCli.tempImageDir (빈 값 제외, 중복 제거)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScreenshotCleanupService {

    private final BotProperties properties;

    @PostConstruct
    public void onStartup() {
        sweep("기동");
    }

    @PreDestroy
    public void onShutdown() {
        sweep("종료");
    }

    private void sweep(String phase) {
        Set<Path> dirs = new LinkedHashSet<>();
        String sysTmp = System.getProperty("java.io.tmpdir");
        if (sysTmp != null && !sysTmp.isBlank()) dirs.add(Paths.get(sysTmp));
        String claudeDir = properties.getClaudeCli().getTempImageDir();
        if (claudeDir != null && !claudeDir.isBlank()) dirs.add(Paths.get(claudeDir));
        String codexDir = properties.getCodexCli().getTempImageDir();
        if (codexDir != null && !codexDir.isBlank()) dirs.add(Paths.get(codexDir));

        int total = 0, failed = 0;
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                for (Path p : (Iterable<Path>) files::iterator) {
                    String n = p.getFileName().toString();
                    if (!n.startsWith("reaction-bot-") || !n.endsWith(".jpg")) continue;
                    try {
                        Files.delete(p);
                        total++;
                    } catch (IOException e) {
                        failed++;
                        log.debug("스크린샷 삭제 실패: {} ({})", p, e.getMessage());
                    }
                }
            } catch (IOException e) {
                log.warn("스크린샷 디렉토리 순회 실패 ({}): {}", dir, e.getMessage());
            }
        }
        if (total == 0 && failed == 0) {
            log.debug("[{}] 스크린샷 정리: 삭제할 파일 없음", phase);
        } else {
            log.info("[{}] 스크린샷 정리 완료: 삭제 {}개{}",
                    phase, total, failed > 0 ? ", 실패 " + failed + "개" : "");
        }
    }
}
