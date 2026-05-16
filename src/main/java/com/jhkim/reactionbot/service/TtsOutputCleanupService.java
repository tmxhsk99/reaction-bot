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
import java.util.stream.Stream;

/**
 * TTS output 디렉토리의 mp3 파일 정리.
 *
 * - 기동 시 (@PostConstruct): 강제 종료 등으로 남은 잔재 청소
 * - 종료 시 (@PreDestroy)  : 정상 종료 시 발자국 청소
 *
 * 대상: {output-dir}/tts-*.mp3 패턴 (UUID로 생성된 파일들).
 * 사용자가 같은 폴더에 둔 다른 파일은 건드리지 않음.
 *
 * 끄려면: reaction-bot.tts.cleanup-on-startup / cleanup-on-shutdown = false
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TtsOutputCleanupService {

    private final BotProperties properties;

    @PostConstruct
    public void onStartup() {
        if (!properties.getTts().isCleanupOnStartup()) {
            log.debug("기동 시 TTS cleanup 비활성 - 건너뜀");
            return;
        }
        performCleanup("기동");
    }

    @PreDestroy
    public void onShutdown() {
        if (!properties.getTts().isCleanupOnShutdown()) {
            log.debug("종료 시 TTS cleanup 비활성 - 건너뜀");
            return;
        }
        performCleanup("종료");
    }

    private void performCleanup(String phase) {
        Path dir = Paths.get(properties.getTts().getOutputDir());
        if (!Files.isDirectory(dir)) {
            return;
        }

        int[] counts = {0, 0};   // [deleted, failed]
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("tts-") && n.endsWith(".mp3");
                    })
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                            counts[0]++;
                        } catch (IOException e) {
                            counts[1]++;
                            log.debug("TTS 파일 삭제 실패: {} ({})", p.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("TTS 출력 디렉토리 순회 실패 ({}): {}", phase, e.getMessage());
            return;
        }

        if (counts[0] == 0 && counts[1] == 0) {
            log.debug("[{}] TTS 출력 정리: 삭제할 파일 없음", phase);
        } else {
            log.info("[{}] TTS 출력 정리 완료: 삭제 {}개{}",
                    phase,
                    counts[0],
                    counts[1] > 0 ? ", 실패 " + counts[1] + "개" : "");
        }
    }
}
