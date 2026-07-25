package com.jhkim.reactionbot.service;

public interface TtsService {
    /**
     * 텍스트를 음성으로 합성하고 오디오 디바이스로 재생.
     * 재생이 끝날 때까지 블로킹.
     */
    void speak(String text);

    /**
     * 현재 진행 중인 발화를 중단. 합성 중(재생 전)이면 재생 자체를 건너뜀.
     * 진행 중인 발화가 없으면 no-op.
     *
     * @return provider 가 스킵을 지원하면 true. 기본 구현은 미지원 — false.
     */
    default boolean skip() {
        return false;
    }
}
