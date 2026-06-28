package com.jhkim.reactionbot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jhkim.reactionbot.config.BotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * 화면 번역 결과를 일자별 JSONL 파일로 영구화 + 별칭 관리.
 *
 * 저장 구조 (history-dir 아래):
 *   YYYY-MM-DD.jsonl   : 한 줄당 한 엔트리 ({"ts":..., "source":..., "translated":..., "speaker":...})
 *   aliases.json       : {"YYYY-MM-DD": "별칭", ...}
 *
 * JSONL 선택 이유: append-only, 부분 손상에 강함, 큰 파일도 라인 단위 스트리밍 처리 가능.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranslationHistoryService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String ALIASES_FILE = "aliases.json";

    private final BotProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${user.dir:.}")
    private String workingDir;

    private final ReentrantLock writeLock = new ReentrantLock();

    public record HistoryEntry(
            String ts,           // ISO 8601
            String speaker,      // optional, null 가능
            String source,       // 원문
            String translated,   // 번역문
            String sourceLang,   // 감지된 소스 언어 ("ja","en","auto" 등)
            String targetLang
    ) {}

    public record DateSummary(
            String date,         // YYYY-MM-DD
            String alias,        // 별칭 (없으면 빈 문자열)
            long entries         // 그 날 엔트리 개수
    ) {}

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(historyDir());
        } catch (IOException e) {
            log.warn("history-dir 생성 실패: {}", e.getMessage());
        }
    }

    /** 새 번역 엔트리 append. 엔트리의 ts(시스템 zone) 기준 일자별 파일에 들어감. */
    public void append(HistoryEntry entry) {
        writeLock.lock();
        try {
            Path file = fileForDate(bucketDate(entry));
            Files.createDirectories(file.getParent());
            String json = objectMapper.writeValueAsString(entry);
            Files.writeString(file, json + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("히스토리 append 실패: {}", e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    /** 엔트리의 ts(ISO 8601 UTC) 를 시스템 zone 으로 변환해 일자 산출. 실패 시 currentDate() 폴백. */
    private String bucketDate(HistoryEntry entry) {
        try {
            return Instant.parse(entry.ts())
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .format(DATE_FMT);
        } catch (Exception ignored) {
            return currentDate();
        }
    }

    /** 일자 리스트 (최근 날짜부터). aliases.json 별칭 포함. */
    public List<DateSummary> listDates() {
        Map<String, String> aliases = loadAliases();
        Path dir = historyDir();
        if (!Files.isDirectory(dir)) return List.of();
        List<DateSummary> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.jsonl"))
                    .forEach(p -> {
                        String date = p.getFileName().toString().replace(".jsonl", "");
                        long lines = countLines(p);
                        String alias = aliases.getOrDefault(date, "");
                        out.add(new DateSummary(date, alias, lines));
                    });
        } catch (IOException e) {
            log.warn("히스토리 listDates 실패: {}", e.getMessage());
        }
        out.sort(Comparator.comparing(DateSummary::date).reversed());
        return out;
    }

    /** 해당 일자 엔트리들 (시간순). 파일 없으면 빈 리스트. */
    public List<HistoryEntry> entriesFor(String date) {
        Path file = fileForDate(date);
        if (!Files.isRegularFile(file)) return List.of();
        List<HistoryEntry> out = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    out.add(objectMapper.readValue(line, HistoryEntry.class));
                } catch (Exception parseErr) {
                    log.debug("히스토리 라인 파싱 실패, 스킵: {}", parseErr.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("히스토리 entriesFor 실패: {}", e.getMessage());
        }
        return out;
    }

    /** 별칭 지정. alias=""면 제거. */
    public void setAlias(String date, String alias) {
        writeLock.lock();
        try {
            Map<String, String> aliases = loadAliases();
            if (alias == null || alias.isBlank()) aliases.remove(date);
            else aliases.put(date, alias.trim());
            Files.createDirectories(historyDir());
            Files.writeString(aliasesFile(), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(aliases),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("aliases 저장 실패: {}", e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    /** 오늘 날짜 (시스템 zone). */
    public String currentDate() {
        return LocalDate.now(ZoneId.systemDefault()).format(DATE_FMT);
    }

    private Path historyDir() {
        String configured = properties.getScreenTranslate().getHistoryDir();
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured);
        }
        return Paths.get(workingDir, "translation-history");
    }

    // YYYY-MM-DD 정확히 매칭. path traversal / 경로 분리자 / 기타 파일명 메타문자 모두 거부.
    private static final java.util.regex.Pattern DATE_RE = java.util.regex.Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private Path fileForDate(String date) {
        if (date == null || !DATE_RE.matcher(date).matches()) {
            throw new IllegalArgumentException("잘못된 date 형식: " + date + " (YYYY-MM-DD 필요)");
        }
        return historyDir().resolve(date + ".jsonl");
    }

    private Path aliasesFile() {
        return historyDir().resolve(ALIASES_FILE);
    }

    private Map<String, String> loadAliases() {
        Path f = aliasesFile();
        if (!Files.isRegularFile(f)) return new LinkedHashMap<>();
        try {
            String json = Files.readString(f, StandardCharsets.UTF_8);
            if (json.isBlank()) return new LinkedHashMap<>();
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (IOException e) {
            log.warn("aliases.json 로드 실패: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private long countLines(Path p) {
        try (Stream<String> s = Files.lines(p, StandardCharsets.UTF_8)) {
            return s.filter(l -> !l.isBlank()).count();
        } catch (IOException e) {
            return 0;
        }
    }
}
