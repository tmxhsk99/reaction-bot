package com.jhkim.reactionbot.service;

/**
 * LLM provider 추상화. Claude / Gemini / 로컬 Ollama 같은 백엔드를 갈아끼울 수 있게 함.
 * 기본은 2단계 흐름:
 *   - triage(): 저렴한 모델로 PASS/SPEAK + (옵션) vision 필요 여부 판단
 *   - generateComment(): 고품질 모델로 실제 코멘트 생성
 * 로컬 LLM처럼 호출 비용이 무관한 경우 hasSeparateTriage()=false로 1회 호출로 단축 가능.
 */
public interface LlmProvider {

    /** generateComment가 PASS를 반환할 때 사용하는 sentinel. */
    String PASS = "__PASS__";

    /**
     * triage 결과.
     *  - PASS              : 봇이 끼어들 상황 아님. 호출 종료.
     *  - SPEAK_WITH_VISION : 발화하되 화면 캡처 필요 (화면 의존 코멘트).
     *  - SPEAK_TEXT_ONLY   : 발화하되 화면 캡처 불필요 (텍스트 잡담·호명 응답 등). 캡처 비용/지연 절감.
     * multimodal-mode=always일 때는 SPEAK_TEXT_ONLY가 와도 orchestrator가 캡처를 강제할 수 있음.
     */
    enum TriageResult { PASS, SPEAK_WITH_VISION, SPEAK_TEXT_ONLY }

    /**
     * PASS/SPEAK 판단만. 히스토리에 영향 없음.
     * 상용 API 비용 절감을 위해 1차 판단에는 이미지를 보내지 않고 텍스트만 사용.
     * @param userText 스트리머 발화 텍스트
     * @param needsVisionDecision true면 SPEAK_WITH_VISION / SPEAK_TEXT_ONLY 구분도 함께 판단,
     *                            false면 SPEAK일 때 항상 SPEAK_WITH_VISION 반환 (기존 동작)
     */
    TriageResult triage(String userText, boolean needsVisionDecision);

    /**
     * 실제 코멘트 생성. 히스토리에 추가됨. 캐릭터 시스템 프롬프트(character.yml) 사용.
     * @return 봇 멘트. provider가 PASS 의도일 때는 {@link #PASS} 반환.
     */
    String generateComment(String userText, String base64JpegImage);

    /**
     * Idle 발화 전용 호출. character.yml 캐릭터 프롬프트는 사용하지 않고,
     * 주어진 idleSystemPrompt만 시스템 프롬프트로 사용. 히스토리는 영향 없음 (참조도/추가도 X).
     * 평소 페르소나가 너무 무거워서 idle에서 어색해지는 걸 막기 위한 분리.
     * @param idleSystemPrompt idle 전용으로 설계된 시스템 프롬프트 (스테이지별 다름)
     * @param triggerText      "(능동 트리거: 한참 조용함...)" 같은 유저 자리 텍스트
     * @param base64JpegImage  화면 캡처 (vision provider일 때만 의미)
     * @return 봇 멘트. PASS 의도면 {@link #PASS}.
     */
    String generateIdleComment(String idleSystemPrompt, String triggerText, String base64JpegImage);

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

    /**
     * 캐릭터 페르소나·히스토리·트리아지 다 무시하고 단발 vision 호출.
     * 포켓몬 오버레이처럼 발화 흐름과 무관한 raw 분석용. 호출자가 준 systemPrompt만 사용.
     * 기본적으로 triage 모델을 사용 (저렴 모델 우선).
     *
     * 기본 구현은 미지원 — provider별로 필요 시 override.
     */
    default String analyzeImage(String systemPrompt, String userPrompt, String base64JpegImage) {
        throw new UnsupportedOperationException(
                "analyzeImage가 이 LLM provider에는 구현되지 않았습니다.");
    }

    /**
     * vision 단발 호출 + 모델 선택. 화면 번역 모드의 stage 1 (triage 모델로 화면 분석) 용도.
     *
     * @param useTriageModel true=triage 모델 사용(저렴), false=메인 모델 사용(고품질).
     *                       triage 모델이 미설정인 provider 는 메인 모델로 폴백.
     *                       triage/main 분리가 없는 provider(ollama 등)는 둘 다 단일 모델.
     */
    default String analyzeImage(String systemPrompt, String userPrompt,
                                String base64JpegImage, boolean useTriageModel) {
        // 기본 구현은 useTriageModel=true 일 때만 기존 3-arg analyzeImage 위임. false면 미지원으로 보고.
        if (useTriageModel) {
            return analyzeImage(systemPrompt, userPrompt, base64JpegImage);
        }
        throw new UnsupportedOperationException(
                "analyzeImage(useTriageModel=false)이 이 provider 에 구현되지 않았습니다.");
    }

    /**
     * text-only 단발 호출. 화면 번역 모드의 stage 2 (메인 모델로 source → translated 번역) 용도.
     * 이미지 없이 시스템/유저 프롬프트만으로 호출. provider 의 model/triageModel 선택은 useTriageModel 로 결정.
     */
    default String analyzeText(String systemPrompt, String userPrompt, boolean useTriageModel) {
        throw new UnsupportedOperationException(
                "analyzeText가 이 LLM provider에는 구현되지 않았습니다.");
    }
}
