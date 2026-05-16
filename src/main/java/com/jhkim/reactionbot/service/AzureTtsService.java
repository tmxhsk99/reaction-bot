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
 * Azure Speech REST API 기반 TTS.
 * scripts/tts_azure.py를 ProcessBuilder로 호출. 키/리전은 환경변수로 전달.
 * 합성된 mp3는 tts-output/ 에 저장 후 AudioPlayer로 재생.
 * reaction-bot.tts.provider=azure 일 때만 활성화 (기본값).
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "reaction-bot.tts.provider", havingValue = "azure", matchIfMissing = true)
@RequiredArgsConstructor
public class AzureTtsService implements TtsService {

    private static final String SCRIPT_PATH = "scripts/tts_azure.py";

    private final BotProperties properties;
    private final AudioPlayer audioPlayer;

    @PostConstruct
    public void init() throws IOException {
        Path dir = Paths.get(properties.getTts().getOutputDir());
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        log.info("TTS 출력 디렉토리: {}", dir.toAbsolutePath());

        String key = properties.getTts().getAzure().getKey();
        String region = properties.getTts().getAzure().getRegion();
        if (key == null || key.isBlank()) {
            log.warn("tts.azure.key (AZURE_SPEECH_KEY) 미설정 - 첫 TTS 호출 시 실패합니다.");
        } else {
            log.info("Azure TTS 준비 완료 (region={}, key=****{})",
                    region,
                    key.length() > 4 ? key.substring(key.length() - 4) : "");
        }
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
        BotProperties.Azure azure = properties.getTts().getAzure();
        pb.environment().put("AZURE_SPEECH_KEY", azure.getKey() == null ? "" : azure.getKey());
        pb.environment().put("AZURE_SPEECH_REGION",
                azure.getRegion() == null ? "koreacentral" : azure.getRegion());
        pb.environment().put("PYTHONIOENCODING", "utf-8");

        try {
            log.debug("Azure TTS 합성 시작: {}", text);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[tts_azure.py] {}", line);
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
            log.debug("Azure TTS 합성 완료: {} ({}바이트)", outputPath, out.length());
            return out;

        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("TTS 합성 실패", e);
        }
    }
}
