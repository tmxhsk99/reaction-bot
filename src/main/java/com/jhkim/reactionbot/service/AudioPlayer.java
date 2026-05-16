package com.jhkim.reactionbot.service;

import javazoom.jl.player.Player;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;

/**
 * mp3 파일을 시스템 기본 출력 디바이스로 재생.
 * OBS의 "데스크탑 오디오"가 자동으로 캡처해서 시청자에게 송출됨.
 * 재생이 끝날 때까지 블로킹 (다음 호출 전에 끝나야 봇이 말 겹치지 않음).
 */
@Slf4j
@Component
public class AudioPlayer {

    public void play(File mp3File) {
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(mp3File))) {
            log.debug("재생 시작: {}", mp3File.getName());
            Player player = new Player(bis);
            player.play();  // 끝날 때까지 블로킹
            log.debug("재생 완료: {}", mp3File.getName());
        } catch (Exception e) {
            throw new RuntimeException("오디오 재생 실패: " + mp3File, e);
        }
    }
}
