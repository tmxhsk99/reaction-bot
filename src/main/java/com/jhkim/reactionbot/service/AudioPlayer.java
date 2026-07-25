package com.jhkim.reactionbot.service;

import javazoom.jl.player.Player;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.function.BooleanSupplier;

/**
 * mp3 파일을 시스템 기본 출력 디바이스로 재생.
 * OBS의 "데스크탑 오디오"가 자동으로 캡처해서 시청자에게 송출됨.
 * 재생이 끝날 때까지 블로킹 (다음 호출 전에 끝나야 봇이 말 겹치지 않음).
 */
@Slf4j
@Component
public class AudioPlayer {

    // 현재 재생 중인 player — stop() 이 다른 스레드에서 close() 호출 (스킵 기능)
    private volatile Player current;

    public void play(File mp3File) {
        play(mp3File, null);
    }

    /**
     * cancelled 가 true 면 재생 시작 전에 중단.
     * player 등록(current) 후에 재확인하므로, 호출 측 취소 플래그 확인 ~ 재생 시작 사이에
     * stop() 이 끼어들어도 스킵이 유실되지 않음.
     */
    public void play(File mp3File, BooleanSupplier cancelled) {
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(mp3File))) {
            log.debug("재생 시작: {}", mp3File.getName());
            Player player = new Player(bis);
            current = player;
            if (cancelled != null && cancelled.getAsBoolean()) {
                log.debug("재생 전 스킵 감지: {}", mp3File.getName());
                return;
            }
            player.play();  // 끝날 때까지 블로킹 (close() 되면 즉시 반환)
            log.debug("재생 완료: {}", mp3File.getName());
        } catch (Exception e) {
            throw new RuntimeException("오디오 재생 실패: " + mp3File, e);
        } finally {
            current = null;
        }
    }

    /** 현재 재생 중인 오디오 중단. 재생 중이 아니면 no-op. */
    public void stop() {
        Player p = current;
        if (p != null) {
            try {
                p.close();  // play() 블로킹이 풀림
                log.debug("재생 스킵 요청 - player close");
            } catch (Exception e) {
                log.warn("재생 중단 실패", e);
            }
        }
    }
}
