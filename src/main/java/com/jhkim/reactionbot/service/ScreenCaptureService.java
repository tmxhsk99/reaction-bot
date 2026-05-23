package com.jhkim.reactionbot.service;

import com.jhkim.reactionbot.config.BotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

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

    private final BotProperties properties;
    private final ObsScreenshotClient obsClient;

    /**
     * 설정된 source에 따라 캡처 → JPEG → Base64 인코딩.
     */
    public String captureBase64Jpeg() {
        String source = properties.getScreen().getSource();
        if ("obs".equalsIgnoreCase(source)) {
            try {
                String base64 = obsClient.captureBase64Jpeg();
                log.debug("OBS 캡처 완료. base64 {}자", base64.length());
                return base64;
            } catch (Exception e) {
                throw new RuntimeException("OBS 캡처 실패: " + e.getMessage(), e);
            }
        }
        BufferedImage image = capture();
        BufferedImage resized = resizeIfNeeded(image);
        byte[] jpegBytes = toJpeg(resized);
        log.debug("Robot 캡처 완료. {}x{} → {}바이트",
                resized.getWidth(), resized.getHeight(), jpegBytes.length);
        return Base64.getEncoder().encodeToString(jpegBytes);
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
        if (src.getWidth() <= TARGET_WIDTH) {
            return src;
        }
        int targetHeight = (int) (src.getHeight() * (TARGET_WIDTH / (double) src.getWidth()));
        BufferedImage resized = new BufferedImage(TARGET_WIDTH, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, TARGET_WIDTH, targetHeight, null);
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
