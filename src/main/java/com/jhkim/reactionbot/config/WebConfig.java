package com.jhkim.reactionbot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 아바타 URL 라우팅:
 *  - GET /avatar, /avatar/                          → forward to index.html (classpath:/static/avatar/)
 *  - GET /avatar/index.html                         → classpath:/static/avatar/ (Spring 기본 정적 서빙)
 *  - GET /avatar/*.png (그 외 이미지 확장자도)      → 파일시스템 file:./assets/avatar/
 *
 * 이미지를 파일시스템에서 서빙하는 이유:
 *  - 이미지 교체 시 빌드/재기동 없이 브라우저 새로고침만으로 반영
 *  - JAR 크기에 큰 PNG가 들어가지 않음
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 홈 (모든 UI / API 진입점 모음)
        registry.addViewController("/").setViewName("forward:/home/index.html");
        registry.addViewController("/home").setViewName("forward:/home/index.html");
        registry.addViewController("/home/").setViewName("forward:/home/index.html");

        registry.addViewController("/avatar").setViewName("forward:/avatar/index.html");
        registry.addViewController("/avatar/").setViewName("forward:/avatar/index.html");
        registry.addViewController("/config").setViewName("forward:/config/index.html");
        registry.addViewController("/config/").setViewName("forward:/config/index.html");
        registry.addViewController("/pokemon-overlay").setViewName("forward:/pokemon-overlay/index.html");
        registry.addViewController("/pokemon-overlay/").setViewName("forward:/pokemon-overlay/index.html");
        registry.addViewController("/translate").setViewName("forward:/translate/index.html");
        registry.addViewController("/translate/").setViewName("forward:/translate/index.html");
        registry.addViewController("/translate/history").setViewName("forward:/translate/history.html");
        registry.addViewController("/translate/history/").setViewName("forward:/translate/history.html");
        registry.addViewController("/translate/debug").setViewName("forward:/translate/debug.html");
        registry.addViewController("/translate/debug/").setViewName("forward:/translate/debug.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 캐릭터 파트 이미지 - assets/avatar/
        registry.addResourceHandler(
                        "/avatar/*.png",
                        "/avatar/*.jpg",
                        "/avatar/*.jpeg",
                        "/avatar/*.gif",
                        "/avatar/*.webp",
                        "/avatar/*.svg")
                .addResourceLocations("file:./assets/avatar/")
                .setCachePeriod(0);

        // 배경 이미지/GIF - assets/bg/
        registry.addResourceHandler(
                        "/bg/*.png",
                        "/bg/*.jpg",
                        "/bg/*.jpeg",
                        "/bg/*.gif",
                        "/bg/*.webp",
                        "/bg/*.mp4")
                .addResourceLocations("file:./assets/bg/")
                .setCachePeriod(0);

        // 포켓몬 오버레이 정적 리소스 - 브라우저 캐싱 끔. 개발 중 HTML/JS 변경 즉시 반영.
        // 안 박으면 옛 데모 폴백 들어있던 JS가 그대로 들고와져 "데모가 계속 뜬다"는 증상 발생.
        registry.addResourceHandler("/pokemon-overlay/**")
                .addResourceLocations("classpath:/static/pokemon-overlay/")
                .setCachePeriod(0);

        // 홈 (랜딩 페이지) 정적 리소스. 캐싱 끔 — 메뉴 자주 바뀔 수 있음.
        registry.addResourceHandler("/home/**")
                .addResourceLocations("classpath:/static/home/")
                .setCachePeriod(0);

        // 화면 번역 모드 정적 리소스 (대화창 UI + 히스토리 페이지). 캐싱 끔.
        registry.addResourceHandler("/translate/**")
                .addResourceLocations("classpath:/static/translate/")
                .setCachePeriod(0);

        // Galmuri 픽셀 폰트 (woff2). /font/galmuri/Galmuri11.woff2 등으로 접근.
        // 폰트는 자주 안 바뀌므로 캐싱 1일.
        registry.addResourceHandler("/font/galmuri/**")
                .addResourceLocations("classpath:/font/galmuri/")
                .setCachePeriod(86400);
    }
}
