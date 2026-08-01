package com.jhkim.reactionbot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.BitSet;

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

    // ── 픽셀 단위 미세 변화 감지 (타자기 연출 보완) ──
    // dHash 는 9x8 로 뭉개서 글자 몇 개 추가되는 변화(hamming 0~2)를 못 본다.
    // 128x72 그레이스케일이면 1280px 캡처의 글자 하나가 2~3px 로 남아 감지 가능.

    public static final int GRAY_GRID_W = 128;
    public static final int GRAY_GRID_H = 72;
    /** 픽셀이 "변했다"고 판정하는 휘도 차 임계. 캡처/JPEG 노이즈 흡수용. */
    public static final int GRAY_LUMA_DELTA = 15;

    /** 128x72 그레이스케일 다운스케일 → 휘도 배열. changedPixels 비교용. */
    public int[] grayGrid(BufferedImage src) {
        BufferedImage resized = new BufferedImage(GRAY_GRID_W, GRAY_GRID_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, GRAY_GRID_W, GRAY_GRID_H, null);
        g.dispose();

        int[] luma = new int[GRAY_GRID_W * GRAY_GRID_H];
        int i = 0;
        for (int y = 0; y < GRAY_GRID_H; y++) {
            for (int x = 0; x < GRAY_GRID_W; x++) {
                int rgb = resized.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int gg = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                luma[i++] = (int) (0.299 * r + 0.587 * gg + 0.114 * b);
            }
        }
        return luma;
    }

    /**
     * 휘도 차가 GRAY_LUMA_DELTA 초과인 픽셀 위치 비트셋.
     * 위치까지 반환하는 이유: 대화창 깜빡이 커서(▼)처럼 "같은 자리 반복 변화"와
     * 타자기 연출처럼 "새 영역이 계속 변하는 것"을 호출자가 구분해야 하기 때문.
     * 배열이 없거나 크기가 다르면 전체 set(=완전 변화) 반환.
     */
    public BitSet changedMask(int[] a, int[] b) {
        int total = GRAY_GRID_W * GRAY_GRID_H;
        BitSet mask = new BitSet(total);
        if (a == null || b == null || a.length != b.length) {
            mask.set(0, total);
            return mask;
        }
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > GRAY_LUMA_DELTA) mask.set(i);
        }
        return mask;
    }
}
