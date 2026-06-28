package com.jhkim.reactionbot.service;

import com.jhkim.reactionbot.config.BotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 화면 캡처. source 설정에 따라 분기:
 *  - "obs"   : OBS WebSocket으로 송출 중인 씬을 받아옴 (시청자가 보는 화면)
 *  - "robot" : java.awt.Robot으로 모니터 전체 캡처
 * Claude vision은 가로 1568px 권장이라 큰 화면은 리사이즈.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScreenCaptureService {

    // VL 모델(qwen3-vl 등)은 이미지를 패치로 토큰화 → 가로폭이 곧 토큰 수.
    // 672로 줄여서 이미지 토큰을 약 1/4로 압축 (OOM 방지).
    private static final int TARGET_WIDTH = 672;

    // 화면이 거의 단색(검은/흰/로딩)인지 판정하는 휘도 표준편차 임계치.
    // 픽셀 휘도(0~255)의 stddev가 이 값보다 낮으면 "내용 없는 화면"으로 보고 이미지를 LLM에 안 보냄.
    // 순수 검정/흰색/단색 로딩은 stddev≈0, 실제 게임 장면은 보통 30↑이라 안전하게 걸러짐.
    private static final double BLANK_LUMA_STDDEV = 8.0;

    // 발화 시작 시점에 미리 캡처해둔 스크린샷. 발화 종료 후 LLM 호출 시 이 캐시를 우선 사용.
    private static final long PRE_CAPTURE_TTL_MS = 30_000;
    private final AtomicReference<PreCapture> preCaptured = new AtomicReference<>();

    private record PreCapture(Capture capture, Instant capturedAt) {}

    private final BotProperties properties;
    private final ObsScreenshotClient obsClient;

    /**
     * 캡처 결과. base64는 정상 캡처일 때만 채워지고, 단색/로딩 화면이면 null + blank=true.
     * 호출 측은 blank=true일 때 LLM 호출 시 "화면 정보 없음 — 발화 텍스트에만 기반해 반응"
     * 힌트를 주입해 LLM이 없는 화면을 추측하지 않도록 한다.
     */
    public record Capture(String base64Jpeg, boolean blank) {
        public static final Capture BLANK = new Capture(null, true);
        public static Capture ok(String base64) { return new Capture(base64, false); }
    }

    /**
     * 설정된 source에 따라 캡처 → JPEG → Base64 인코딩.
     * 화면이 단색(검은/흰/로딩)으로 판정되면 {@link Capture#BLANK} 반환.
     */
    public Capture captureBase64Jpeg() {
        String source = properties.getScreen().getSource();
        if ("obs".equalsIgnoreCase(source)) {
            try {
                String base64 = obsClient.captureBase64Jpeg();
                if (isBlankBase64Jpeg(base64)) {
                    log.info("단색/로딩 화면 감지 — 이미지 생략 (OBS)");
                    return Capture.BLANK;
                }
                log.debug("OBS 캡처 완료. base64 {}자", base64.length());
                return Capture.ok(base64);
            } catch (Exception e) {
                throw new RuntimeException("OBS 캡처 실패: " + e.getMessage(), e);
            }
        }
        BufferedImage image = capture();
        BufferedImage resized = resizeIfNeeded(image);
        if (isBlank(resized)) {
            log.info("단색/로딩 화면 감지 — 이미지 생략 (Robot)");
            return Capture.BLANK;
        }
        byte[] jpegBytes = toJpeg(resized);
        log.debug("Robot 캡처 완료. {}x{} → {}바이트",
                resized.getWidth(), resized.getHeight(), jpegBytes.length);
        return Capture.ok(Base64.getEncoder().encodeToString(jpegBytes));
    }

    /**
     * 발화 시작 시점에 호출. 스크린샷을 미리 캡처해서 캐시.
     * 유저가 "이거 봐" 하며 가리킨 화면을 LLM이 실제로 보게 하려면
     * 발화 시작 시점의 화면이 가장 정확하다.
     */
    public void preCaptureForSpeech() {
        try {
            Capture cap = captureBase64Jpeg();
            preCaptured.set(new PreCapture(cap, Instant.now()));
            log.info("프리캡처 완료 (blank={})", cap.blank());
        } catch (Exception e) {
            log.warn("프리캡처 실패: {}", e.getMessage());
            preCaptured.set(null);
        }
    }

    /**
     * 프리캡처된 스크린샷을 꺼냄 (1회 소비). TTL 초과 시 null 반환.
     */
    public Capture consumePreCaptured() {
        PreCapture pc = preCaptured.getAndSet(null);
        if (pc == null) return null;
        long ageMs = java.time.Duration.between(pc.capturedAt(), Instant.now()).toMillis();
        if (ageMs > PRE_CAPTURE_TTL_MS) {
            log.debug("프리캡처 TTL 초과 ({}ms). 폐기.", ageMs);
            return null;
        }
        log.debug("프리캡처 사용 (age={}ms)", ageMs);
        return pc.capture();
    }

    /**
     * 크롭 영역 선택 UI 용. 단색 판정 없이, 큰 해상도(원본/OBS 1080p)를 그대로 PNG base64 반환.
     * 자동 번역 흐름에서 쓰는 captureBase64Jpeg() 와는 별도. 사용자가 드래그로 정확한 영역을 잡으려면
     * 리사이즈 전의 충분히 큰 이미지가 필요해서 분리.
     */
    public SampleCapture captureSampleForSelection() {
        String source = properties.getScreen().getSource();
        BufferedImage img;
        if ("obs".equalsIgnoreCase(source)) {
            try {
                String base64Jpeg = obsClient.captureBase64Jpeg();
                byte[] bytes = Base64.getDecoder().decode(base64Jpeg);
                img = ImageIO.read(new ByteArrayInputStream(bytes));
                if (img == null) {
                    throw new RuntimeException("OBS 캡처 디코드 실패");
                }
            } catch (Exception e) {
                throw new RuntimeException("OBS 캡처 실패: " + e.getMessage(), e);
            }
        } else {
            img = capture();
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", baos);
            return new SampleCapture(
                    Base64.getEncoder().encodeToString(baos.toByteArray()),
                    img.getWidth(), img.getHeight());
        } catch (Exception e) {
            throw new RuntimeException("PNG 인코딩 실패: " + e.getMessage(), e);
        }
    }

    public record SampleCapture(String base64Png, int width, int height) {}

    /**
     * 화면 번역 전용. crop-region 적용 + 리사이즈 + 휘도 통계 + blank 판정.
     * 호출자가 target width 지정 (화면 번역은 자막 가독성을 위해 reaction-bot 보다 큰 값 권장).
     * @param blankLumaStddevThreshold 휘도 stddev 가 이 값 미만이면 blank
     * @param targetWidth 0 이하면 reaction-bot 기본(672) 사용
     */
    public TranslateCapture captureForTranslate(String cropRegion, double blankLumaStddevThreshold, int targetWidth) {
        String source = properties.getScreen().getSource();
        BufferedImage raw;
        if ("obs".equalsIgnoreCase(source)) {
            try {
                String base64Jpeg = obsClient.captureBase64Jpeg();
                byte[] bytes = Base64.getDecoder().decode(base64Jpeg);
                raw = ImageIO.read(new ByteArrayInputStream(bytes));
                if (raw == null) throw new RuntimeException("OBS 캡처 디코드 실패");
            } catch (Exception e) {
                throw new RuntimeException("OBS 캡처 실패: " + e.getMessage(), e);
            }
        } else {
            raw = capture();
        }
        int rawW = raw.getWidth();
        int rawH = raw.getHeight();
        BufferedImage cropped = applyCrop(raw, cropRegion);
        int useWidth = targetWidth > 0 ? targetWidth : TARGET_WIDTH;
        BufferedImage resized = resizeIfNeeded(cropped, useWidth);
        LumaStats stats = lumaStats(resized);
        boolean blank = (blankLumaStddevThreshold > 0) && (stats.stddev < blankLumaStddevThreshold);
        if (blank) {
            return new TranslateCapture(null, true, resized,
                    rawW, rawH, resized.getWidth(), resized.getHeight(),
                    stats.mean, stats.stddev, blankLumaStddevThreshold);
        }
        byte[] jpegBytes = toJpeg(resized);
        return new TranslateCapture(
                Base64.getEncoder().encodeToString(jpegBytes), false, resized,
                rawW, rawH, resized.getWidth(), resized.getHeight(),
                stats.mean, stats.stddev, blankLumaStddevThreshold);
    }

    /** 진단 정보 풍부판. 디버그 페이지용. PNG base64 도 함께 채워서 화면에 즉시 표시 가능. */
    public DiagCapture captureForTranslateDiag(String cropRegion, double blankLumaStddevThreshold, int targetWidth) {
        TranslateCapture cap = captureForTranslate(cropRegion, blankLumaStddevThreshold, targetWidth);
        String pngBase64;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(cap.image(), "png", baos);
            pngBase64 = Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            pngBase64 = "";
            log.warn("diag PNG 인코딩 실패: {}", e.getMessage());
        }
        return new DiagCapture(
                properties.getScreen().getSource(),
                cap.rawWidth(), cap.rawHeight(),
                cropRegion == null ? "" : cropRegion,
                cap.processedWidth(), cap.processedHeight(),
                cap.lumaMean(), cap.lumaStddev(), cap.blankThreshold(), cap.blank(),
                pngBase64);
    }

    public record TranslateCapture(
            String base64Jpeg, boolean blank, BufferedImage image,
            int rawWidth, int rawHeight,
            int processedWidth, int processedHeight,
            double lumaMean, double lumaStddev, double blankThreshold) {}

    public record DiagCapture(
            String source,            // "obs" | "robot"
            int rawWidth, int rawHeight,
            String cropRegion,
            int processedWidth, int processedHeight,
            double lumaMean, double lumaStddev, double blankThreshold, boolean blank,
            String processedImageBase64Png) {}

    private record LumaStats(double mean, double stddev) {}

    /** isBlank() 의 통계 산출만 분리. 임계 비교는 호출자가. */
    private LumaStats lumaStats(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        if (w == 0 || h == 0) return new LumaStats(0, 0);
        int stepX = Math.max(1, w / 60);
        int stepY = Math.max(1, h / 60);
        double sum = 0, sumSq = 0;
        int n = 0;
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                double luma = 0.299 * r + 0.587 * g + 0.114 * b;
                sum += luma;
                sumSq += luma * luma;
                n++;
            }
        }
        if (n == 0) return new LumaStats(0, 0);
        double mean = sum / n;
        double variance = Math.max(0, sumSq / n - mean * mean);
        return new LumaStats(mean, Math.sqrt(variance));
    }

    /** crop-region "x,y,w,h" (0~1 정규화) 적용. 빈 값/잘못된 형식이면 원본 그대로. */
    private BufferedImage applyCrop(BufferedImage src, String cropRegion) {
        if (cropRegion == null || cropRegion.isBlank()) return src;
        try {
            String[] parts = cropRegion.split(",");
            if (parts.length != 4) return src;
            double x = clamp01(Double.parseDouble(parts[0].trim()));
            double y = clamp01(Double.parseDouble(parts[1].trim()));
            double w = clamp01(Double.parseDouble(parts[2].trim()));
            double h = clamp01(Double.parseDouble(parts[3].trim()));
            int sx = (int) Math.round(x * src.getWidth());
            int sy = (int) Math.round(y * src.getHeight());
            int sw = Math.max(1, (int) Math.round(w * src.getWidth()));
            int sh = Math.max(1, (int) Math.round(h * src.getHeight()));
            sw = Math.min(sw, src.getWidth() - sx);
            sh = Math.min(sh, src.getHeight() - sy);
            return src.getSubimage(sx, sy, sw, sh);
        } catch (Exception e) {
            log.debug("crop-region 적용 실패, 원본 사용: {}", e.getMessage());
            return src;
        }
    }

    private static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }

    /** base64 JPEG를 디코드해 단색 여부 판정. 디코드 실패 시 false(=그대로 전송)로 안전 폴백. */
    private boolean isBlankBase64Jpeg(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) return false;
            return isBlank(img);
        } catch (Exception e) {
            log.debug("blank 판정용 디코드 실패, 이미지 그대로 전송: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 이미지가 거의 단색이면 true. 격자 샘플의 휘도 표준편차로 판정.
     * 검은/흰/단색 로딩 화면은 stddev가 매우 낮고, 실제 장면은 높다.
     */
    private boolean isBlank(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        if (w == 0 || h == 0) return false;

        int stepX = Math.max(1, w / 60);   // 최대 ~60x60 격자만 샘플 (디코드된 이미지 전체 순회 회피)
        int stepY = Math.max(1, h / 60);
        double sum = 0;
        double sumSq = 0;
        int n = 0;
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                double luma = 0.299 * r + 0.587 * g + 0.114 * b;
                sum += luma;
                sumSq += luma * luma;
                n++;
            }
        }
        if (n == 0) return false;
        double mean = sum / n;
        double variance = Math.max(0, sumSq / n - mean * mean);
        double stddev = Math.sqrt(variance);
        boolean blank = stddev < BLANK_LUMA_STDDEV;
        if (blank) {
            log.debug("blank 판정: mean={}, stddev={} (임계 {})",
                    String.format("%.1f", mean), String.format("%.1f", stddev), BLANK_LUMA_STDDEV);
        }
        return blank;
    }

    private BufferedImage capture() {
        int monitorIndex = properties.getScreen().getMonitorIndex();
        GraphicsDevice[] devices = GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getScreenDevices();
        if (monitorIndex >= devices.length) {
            log.warn("모니터 인덱스 {} 이 없음. 사용 가능: {}개. 0번 사용.",
                    monitorIndex, devices.length);
            monitorIndex = 0;
        }
        GraphicsDevice device = devices[monitorIndex];
        Rectangle bounds = device.getDefaultConfiguration().getBounds();
        try {
            Robot robot = new Robot(device);
            return robot.createScreenCapture(bounds);
        } catch (AWTException e) {
            throw new RuntimeException("화면 캡처 실패", e);
        }
    }

    private BufferedImage resizeIfNeeded(BufferedImage src) {
        return resizeIfNeeded(src, TARGET_WIDTH);
    }

    private BufferedImage resizeIfNeeded(BufferedImage src, int targetWidth) {
        if (src.getWidth() <= targetWidth) {
            return src;
        }
        int targetHeight = (int) (src.getHeight() * (targetWidth / (double) src.getWidth()));
        BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, targetWidth, targetHeight, null);
        g.dispose();
        return resized;
    }

    private byte[] toJpeg(BufferedImage image) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            // JPEG 인코더에 RGB 이미지 필요 (alpha 없는)
            BufferedImage rgb = image;
            if (image.getType() != BufferedImage.TYPE_INT_RGB) {
                rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rgb.createGraphics();
                g.drawImage(image, 0, 0, Color.BLACK, null);
                g.dispose();
            }
            ImageIO.write(rgb, "jpg", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("JPEG 인코딩 실패", e);
        }
    }
}
