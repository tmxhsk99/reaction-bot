package com.jhkim.reactionbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ReactionBotApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ReactionBotApplication.class);
        app.setHeadless(false);
        app.run(args);
    }
}
