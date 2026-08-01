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

    /**
     * 이월된 스킵 요청 제거. 새 발화 lifecycle 시작 시점에 호출 —
     * 직전 발화가 자연 종료된 뒤 늦게 도착한 skip() 이 다음 발화를 죽이는 것 방지.
     * 기본 구현은 no-op.
     */
    default void clearSkip() {
    }
}
