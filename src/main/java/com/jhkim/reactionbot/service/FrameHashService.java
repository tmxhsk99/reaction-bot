package com.jhkim.reactionbot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * dHash (difference hash). 9x8로 그레이스케일 리사이즈 후 인접 픽셀 비교로 64bit 해시.
 * 비교는 두 해시의 hamming distance (다른 비트 수). 0=동일, 5~10이면 거의 같은 화면, 20+는 다른 화면.
 *
 * 화면 번역 prefilter 용:
 *  - 직전 프레임과 hamming distance ≤ 임계치(기본 5) 면 LLM 호출 스킵
 *  - 임계치 초과 시에만 cheap LLM(triage) 호출
 */
@Slf4j
@Service
public class FrameHashService {

    /**
     * dHash 계산. 9x8 그레이스케일 리사이즈 → 가로 인접 픽셀 비교 → 64bit.
     */
    public long dhash(BufferedImage src) {
        BufferedImage resized = new BufferedImage(9, 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, 9, 8, null);
        g.dispose();

        int[][] luma = new int[8][9];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 9; x++) {
                int rgb = resized.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int gg = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                luma[y][x] = (int) (0.299 * r + 0.587 * gg + 0.114 * b);
            }
        }
        long hash = 0L;
        int bit = 63;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                if (luma[y][x] > luma[y][x + 1]) hash |= (1L << bit);
                bit--;
            }
        }
        return hash;
    }

    /** 두 해시 hamming distance (0~64). 작을수록 비슷. */
    public int hamming(long a, long b) {
        return Long.bitCount(a ^ b);
    }
}
