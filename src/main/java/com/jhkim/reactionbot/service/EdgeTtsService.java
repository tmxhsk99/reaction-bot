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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Microsoft Edge 무료 TTS (유일하게 지원되는 TTS provider).
 * scripts/tts_edge.py를 ProcessBuilder로 호출. API 키 불필요.
 * 한국어 음성은 현재 3개만 가용 (SunHi, InJoon, HyunsuMultilingual).
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "reaction-bot.tts.provider", havingValue = "edge", matchIfMissing = true)
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

        // 본문(text)은 stdin으로 전달. 명령줄 인자로 넘기면 따옴표 등 특수문자에서
        // OS별 인자 인용이 깨져(특히 Windows의 닫히지 않은 ") 뒤 인자들이 흡수됨.
        ProcessBuilder pb = new ProcessBuilder(
                properties.getTts().getPythonExecutable(),
                SCRIPT_PATH,
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

            // 본문을 stdin으로 쓰고 닫음 → 스크립트가 sys.stdin에서 읽음.
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(text.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }

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
