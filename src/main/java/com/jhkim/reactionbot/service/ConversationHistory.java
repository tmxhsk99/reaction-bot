package com.jhkim.reactionbot.service;

import com.jhkim.reactionbot.config.BotProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 최근 N턴 대화를 메모리에 유지.
 * 단순 인메모리. 서버 재시작하면 날아감 (방송 한 회차 용도라 OK).
 */
@Service
@RequiredArgsConstructor
public class ConversationHistory {

    private final BotProperties properties;
    private final Deque<Turn> turns = new ArrayDeque<>();

    public record Turn(String role, String content) {}

    public synchronized void addUser(String content) {
        turns.addLast(new Turn("user", content));
        trim();
    }

    public synchronized void addAssistant(String content) {
        turns.addLast(new Turn("assistant", content));
        trim();
    }

    public synchronized List<Turn> snapshot() {
        return new ArrayList<>(turns);
    }

    public synchronized void clear() {
        turns.clear();
    }

    /** 마지막 턴 제거 (PASS 응답일 때 user 턴 롤백용). */
    public synchronized void popLast() {
        turns.pollLast();
    }

    private void trim() {
        int maxMessages = properties.getHistory().getMaxTurns() * 2; // user+assistant = 1턴
        while (turns.size() > maxMessages) {
            turns.pollFirst();
        }
    }
}
