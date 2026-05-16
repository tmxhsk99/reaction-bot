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
        registry.addViewController("/avatar").setViewName("forward:/avatar/index.html");
        registry.addViewController("/avatar/").setViewName("forward:/avatar/index.html");
        registry.addViewController("/config").setViewName("forward:/config/index.html");
        registry.addViewController("/config/").setViewName("forward:/config/index.html");
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
    }
}
