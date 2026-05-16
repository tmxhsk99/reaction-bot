package com.jhkim.reactionbot.controller;

import com.jhkim.reactionbot.service.UserConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigController {

    private final UserConfigService userConfig;

    /** 현재 설정 (시크릿 마스킹). UI가 GET해서 화면에 채움. */
    @GetMapping
    public Map<String, Object> get() {
        return userConfig.readForUi();
    }

    /** UI가 폼 제출. body에 평문 키-값 (마스킹된 값은 무시). */
    @PostMapping
    public Map<String, Object> save(@RequestBody Map<String, Object> incoming) throws IOException {
        Map<String, Object> result = userConfig.write(incoming);
        return Map.of(
                "status", "ok",
                "message", "저장 완료. 적용하려면 서버를 재기동하세요.",
                "savedAt", result.get("saved"));
    }
}
