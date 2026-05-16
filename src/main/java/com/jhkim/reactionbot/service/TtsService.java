package com.jhkim.reactionbot.service;

public interface TtsService {
    /**
     * 텍스트를 음성으로 합성하고 오디오 디바이스로 재생.
     * 재생이 끝날 때까지 블로킹.
     */
    void speak(String text);
}
