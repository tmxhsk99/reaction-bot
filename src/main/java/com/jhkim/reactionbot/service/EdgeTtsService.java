package com.jhkim.reactionbot.service;

import com.jhkim.reactionbot.config.BotProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Microsoft Edge 무료 TTS.
 * scripts/tts_edge.py를 ProcessBuilder로 호출. API 키 불필요.
 * 한국어 음성은 현재 3개만 가용 (SunHi, InJoon, HyunsuMultilingual).
 * reaction-bot.tts.provider=edge 일 때만 활성화.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "reaction-bot.tts.provider", havingValue = "edge")
@RequiredArgsConstructor
public class EdgeTtsService implements TtsService {

    private static final String SCRIPT_PATH = "scripts/tts_edge.py";

    private final BotProperties properties;
    private final AudioPlayer audioPlayer;

    @PostConstruct
    public void init() throws IOException {
        Path dir = Paths.get(properties.getTts().getOutputDir());
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        log.info("TTS 출력 디렉토리: {}", dir.toAbsolutePath());
        log.info("Edge TTS 준비 완료 (가용 한국어 음성: SunHi/InJoon/HyunsuMultilingual)");
    }

    @Override
    public void speak(String text) {
        if (text == null || text.isBlank()) {
            log.warn("빈 텍스트 - TTS 스킵");
            return;
        }

        File outputFile = synthesize(text);
        audioPlayer.play(outputFile);
    }

    private File synthesize(String text) {
        String filename = "tts-" + UUID.randomUUID() + ".mp3";
        Path outputPath = Paths.get(properties.getTts().getOutputDir(), filename);

        ProcessBuilder pb = new ProcessBuilder(
                properties.getTts().getPythonExecutable(),
                SCRIPT_PATH,
                "--text", text,
                "--voice", properties.getTts().getVoice(),
                "--rate", properties.getTts().getRate(),
                "--pitch", properties.getTts().getPitch(),
                "--output", outputPath.toString()
        );
        pb.redirectErrorStream(true);
        pb.environment().put("PYTHONIOENCODING", "utf-8");

        try {
            log.debug("Edge TTS 합성 시작: {}", text);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[tts_edge.py] {}", line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("TTS 스크립트 실패. exitCode=" + exitCode);
            }

            File out = outputPath.toFile();
            if (!out.exists() || out.length() == 0) {
                throw new RuntimeException("TTS 결과 파일 없음: " + outputPath);
            }
            log.debug("Edge TTS 합성 완료: {} ({}바이트)", outputPath, out.length());
            return out;

        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("TTS 합성 실패", e);
        }
    }
}
