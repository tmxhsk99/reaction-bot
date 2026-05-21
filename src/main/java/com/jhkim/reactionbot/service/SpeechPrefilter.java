package com.jhkim.reactionbot.service;

import com.jhkim.reactionbot.config.BotProperties;
import com.jhkim.reactionbot.config.CharacterConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 발화를 Claude로 보내기 전 1차 필터.
 *  - 명백히 의미 없는 발화(필러, 짧은 추임새)는 컷 → Claude 호출 자체 안 함
 *  - 봇 이름 호명 감지 → 무조건 응답 필요 (fast path)
 * 토큰 절감과 직접 질문 응답성 둘 다 챙김.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpeechPrefilter {

    private final BotProperties properties;
    private final CharacterConfig character;

    private List<Pattern> fillerPatterns;
    private Pattern directAddressPattern;

    @PostConstruct
    public void init() {
        fillerPatterns = new ArrayList<>();
        for (String p : properties.getSpeech().getFillerPatterns()) {
            try {
                fillerPatterns.add(Pattern.compile(p));
            } catch (Exception e) {
                log.warn("필러 정규식 컴파일 실패 '{}': {}", p, e.getMessage());
            }
        }

        // 봇 이름 + 호격 조사. 예: 리봇야, 리봇아, 리봇!, 리봇?, "리봇" 단독
        String name = Pattern.quote(character.getName());
        directAddressPattern = Pattern.compile(
                "(^|[\\s,.!?])" + name + "(야|아|이|님)?[\\s,.!?]?",
                Pattern.CASE_INSENSITIVE);

        log.info("Prefilter 준비 완료: 필러 {}개, 호명 패턴='{}'",
                fillerPatterns.size(), directAddressPattern.pattern());
    }

    public enum Decision {
        FILTER_OUT,     // 명백한 노이즈 — Claude 호출 안 함
        DIRECT_ADDRESS, // 호명됨 — Triage 건너뛰고 바로 코멘트 생성
        NORMAL          // 일반 — Triage 거쳐서 PASS/SPEAK 판단
    }

    public Decision decide(String text) {
        if (text == null) return Decision.FILTER_OUT;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return Decision.FILTER_OUT;

        // 1) 호명 감지 — 다른 모든 필터보다 우선
        if (directAddressPattern.matcher(trimmed).find()) {
            return Decision.DIRECT_ADDRESS;
        }

        // 2) 필러 패턴
        for (Pattern p : fillerPatterns) {
            if (p.matcher(trimmed).matches()) {
                return Decision.FILTER_OUT;
            }
        }

        // 3) Whisper 반복 hallucination ("찹찹찹", "스테이크스테이크", "러시아러시아" 등)
        //    컨트롤러 클릭 같은 비음성 노이즈에서 STT가 같은 토큰을 연속으로 뱉는 패턴.
        if (isRepetitionHallucination(trimmed)) {
            log.debug("반복 hallucination 컷: '{}'", trimmed);
            return Decision.FILTER_OUT;
        }

        return Decision.NORMAL;
    }

    /**
     * 텍스트가 동일한 짧은 substring(1~4글자)의 반복으로 거의 채워져 있으면 true.
     * Whisper는 짧은 노이즈에 대해 같은 토큰을 2~5회 반복해서 뱉는 경향이 있음.
     */
    private boolean isRepetitionHallucination(String text) {
        // 공백/구두점/말줄임표 제거 후 순수 문자열로 판정
        String cleaned = text.replaceAll("[\\s.,!?~…\\-]+", "");
        if (cleaned.length() < 4) return false;

        int maxUnitLen = Math.min(4, cleaned.length() / 2);
        for (int unitLen = 1; unitLen <= maxUnitLen; unitLen++) {
            String unit = cleaned.substring(0, unitLen);
            int matches = 0;
            for (int i = 0; i + unitLen <= cleaned.length(); i += unitLen) {
                if (cleaned.startsWith(unit, i)) matches++;
                else break;
            }
            // 같은 unit이 2번 이상 연속 + 전체의 70% 이상을 커버하면 hallucination 판정
            if (matches >= 2 && matches * unitLen * 100 / cleaned.length() >= 70) {
                return true;
            }
        }
        return false;
    }
}
