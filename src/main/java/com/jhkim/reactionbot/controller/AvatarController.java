package com.jhkim.reactionbot.controller;

import com.jhkim.reactionbot.service.AvatarEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
public class AvatarController {

    private final AvatarEventService avatarEvents;

    /** OBS Browser Source(혹은 다른 클라)가 구독하는 SSE 스트림. */
    @GetMapping(value = "/api/avatar/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return avatarEvents.subscribe();
    }

    /**
     * SSE 동작 단독 테스트용. GET /api/avatar/test → speak_start 발행, 3초 뒤 speak_end.
     * 브라우저에서 /avatar/ 띄워둔 상태로 호출하면 입이 3초간 움직여야 정상.
     */
    @GetMapping("/api/avatar/test")
    public String test() {
        avatarEvents.speakStart();
        new Thread(() -> {
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            avatarEvents.speakEnd();
        }, "avatar-test").start();
        return "speak_start fired, speak_end in 3s";
    }
}
