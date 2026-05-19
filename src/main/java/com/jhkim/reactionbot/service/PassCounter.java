package com.jhkim.reactionbot.service;

import com.jhkim.reactionbot.config.BotProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 연속 PASS 카운터 + 임계치 초과 시 system prompt에 붙일 nudge 문자열 생성.
 * provider 바깥에 두어 Claude/Gemini 양쪽이 같은 카운터를 공유.
 */
@Component
@RequiredArgsConstructor
public class PassCounter {

    private final BotProperties properties;
    private final AtomicInteger streak = new AtomicInteger(0);

    public void increment() {
        streak.incrementAndGet();
    }

    public void reset() {
        streak.set(0);
    }

    public int get() {
        return streak.get();
    }

    /**
     * 연속 PASS가 임계치 이상이면 system prompt 끝에 붙일 nudge 문자열 반환.
     * 임계치 미달이거나 비활성이면 빈 문자열.
     * @param mode "triage" 또는 "comment"
     */
    public String buildNudge(String mode) {
        int threshold = properties.getSpeech().getNudgeAfterPassCount();
        if (threshold <= 0) return "";
        int s = streak.get();
        if (s < threshold) return "";
        if ("triage".equals(mode)) {
            return "\n\n[강제 힌트] 직전 " + s + "번 연속 PASS였음. 이번엔 SPEAK 쪽으로 판단해라.";
        }
        return "\n\n[강제 힌트] 직전 " + s + "번 연속 PASS였음. 이번엔 반드시 한 마디 해. PASS 금지.";
    }
}
