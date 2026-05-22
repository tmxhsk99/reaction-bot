package com.jhkim.reactionbot.service;

/**
 * LLM provider 추상화. Claude / Gemini / 로컬 Ollama 같은 백엔드를 갈아끼울 수 있게 함.
 * 기본은 2단계 흐름:
 *   - triage(): 저렴한 모델로 PASS/SPEAK만 판단
 *   - generateComment(): 고품질 모델로 실제 코멘트 생성
 * 로컬 LLM처럼 호출 비용이 무관한 경우 hasSeparateTriage()=false로 1회 호출로 단축 가능.
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

    /**
     * triage()를 별도 호출로 수행할지.
     * - true (기본): orchestrator가 triage → generateComment 2단계 호출 (상용 API 비용 절감)
     * - false: orchestrator가 triage 단계를 생략하고 바로 generateComment 1회만 호출
     *          (로컬 LLM처럼 비용 무관·속도 우선일 때)
     */
    default boolean hasSeparateTriage() { return true; }

    /**
     * generateComment에 화면 이미지가 의미 있는지.
     * - true (기본): orchestrator가 화면 캡처해서 넘김 (vision 모델용)
     * - false: orchestrator가 화면 캡처 자체를 생략 (text-only 로컬 모델용. 속도↑)
     */
    default boolean acceptsImage() { return true; }
}
