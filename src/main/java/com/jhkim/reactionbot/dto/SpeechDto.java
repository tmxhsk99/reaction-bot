package com.jhkim.reactionbot.dto;

public class SpeechDto {

    public record Request(String text) {}

    public record Response(String result, String botText) {}
}
