package com.jhkim.reactionbot.service;

/**
 * LLM provider 추상화. Claude / Gemini 같은 백엔드를 갈아끼울 수 있게 함.
 * 2단계 흐름은 동일:
 *   - triage(): 저렴한 모델로 PASS/SPEAK만 판단
 *   - generateComment(): 고품질 모델로 실제 코멘트 생성
 */
public interface LlmProvider {

    /** generateComment가 PASS를 반환할 때 사용하는 sentinel. */
    String PASS = "__PASS__";

    /**
     * PASS/SPEAK 판단만. 히스토리에 영향 없음.
     * 상용 API 비용 절감을 위해 1차 판단에는 이미지를 보내지 않고 텍스트만 사용.
     * @return true=SPEAK, false=PASS
     */
    boolean triage(String userText);

    /**
     * 실제 코멘트 생성. 히스토리에 추가됨.
     * @return 봇 멘트. provider가 PASS 의도일 때는 {@link #PASS} 반환.
     */
    String generateComment(String userText, String base64JpegImage);
}
