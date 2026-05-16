package com.jhkim.reactionbot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 아바타(브라우저) 측으로 TTS 시작/종료 이벤트를 SSE로 푸시.
 * 여러 클라이언트(예: OBS Browser Source 여러 개)에 동시 브로드캐스트.
 */
@Slf4j
@Service
public class AvatarEventService {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /** 브라우저가 GET /api/avatar/events 로 구독. emitter는 끊길 때까지 살아있음. */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);  // timeout 0 = 무제한
        emitters.add(emitter);
        emitter.onCompletion(() -> {
            emitters.remove(emitter);
            log.debug("아바타 클라이언트 disconnect (남은 {}명)", emitters.size());
        });
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        log.info("아바타 클라이언트 connect (총 {}명)", emitters.size());
        // 연결 직후 한 번 ping 보내서 정상 연결 확인
        try {
            emitter.send(SseEmitter.event().name("hello").data("connected"));
        } catch (IOException ignored) {
        }
        return emitter;
    }

    public void speakStart() {
        publish("speak_start", "1");
    }

    public void speakEnd() {
        publish("speak_end", "1");
    }

    private void publish(String eventName, String data) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (Throwable t) {
                // 클라이언트 disconnect / 네트워크 오류 → emitter 폐기. 정상 케이스이므로 debug로만.
                log.debug("아바타 SSE emitter 제거: {}", t.getMessage());
                emitters.remove(emitter);
                try {
                    emitter.completeWithError(t);
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
