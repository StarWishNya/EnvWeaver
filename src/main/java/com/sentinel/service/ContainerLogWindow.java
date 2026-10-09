package com.sentinel.service;

import com.sentinel.config.BusinessException;
import com.sentinel.enums.ErrorCode;
import org.springframework.util.StringUtils;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;

/**
 * 任务日志的 tail / since 校验。
 * since 优先交给 Docker 过滤。这里只兜底：行首还能解析出时间戳、且早于 since 的行丢掉；
 * 没有时间戳的行保留。Docker timestamps 加在行首的那一层前缀要去掉，正文里原有的时间戳留下。
 *
 * @author Sentinel Team
 */
public final class ContainerLogWindow {

    public static final int DEFAULT_TAIL = 100;
    public static final int MIN_TAIL = 1;
    public static final int MAX_TAIL = 5000;

    public static final String TAIL_RANGE_MESSAGE = "tail 必须在 1 到 5000 之间";
    public static final String SINCE_FORMAT_MESSAGE = "since 必须是 ISO-8601 日期时间";

    private ContainerLogWindow() {
    }

    public static void checkTail(int tail) {
        if (tail < MIN_TAIL || tail > MAX_TAIL) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, TAIL_RANGE_MESSAGE);
        }
    }

    /**
     * 空白视为未传。支持带偏移的 ISO-8601，以及无时区的本地日期时间（按系统时区解释）。
     */
    public static OffsetDateTime parseSince(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String text = raw.trim();
        try {
            TemporalAccessor parsed = DateTimeFormatter.ISO_DATE_TIME.parseBest(
                    text, OffsetDateTime::from, LocalDateTime::from);
            if (parsed instanceof OffsetDateTime offsetDateTime) {
                return offsetDateTime;
            }
            return ((LocalDateTime) parsed).atZone(ZoneId.systemDefault()).toOffsetDateTime();
        } catch (DateTimeException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, SINCE_FORMAT_MESSAGE);
        }
    }

    /**
     * 按换行计行。末尾换行不额外计一行；空白内容为 0 行。
     */
    public static int countLines(String logs) {
        if (logs == null || logs.isEmpty()) {
            return 0;
        }
        int end = logs.length();
        if (logs.charAt(end - 1) == '\n') {
            end--;
            if (end == 0) {
                return 0;
            }
        }
        int count = 1;
        for (int i = 0; i < end; i++) {
            if (logs.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    /**
     * since 为空时原样返回。无法识别时间戳的行保留。
     */
    public static String filterSince(String logs, OffsetDateTime since) {
        if (logs == null || logs.isEmpty() || since == null) {
            return logs == null ? "" : logs;
        }
        String[] lines = logs.split("\n", -1);
        boolean trailingNewline = logs.endsWith("\n");
        int limit = trailingNewline ? lines.length - 1 : lines.length;
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            if (isBeforeSince(lines[i], since)) {
                continue;
            }
            if (kept.length() > 0) {
                kept.append('\n');
            }
            kept.append(lines[i]);
        }
        if (kept.length() > 0 && trailingNewline) {
            kept.append('\n');
        }
        return kept.toString();
    }

    /**
     * 每一条非空行都以带时区的时间戳加空格开头时，这些前缀是 Docker timestamps 加上的。
     * 只要有一行没有这种前缀，时间戳就属于日志正文。
     */
    public static boolean uniformDockerTimestampPrefixes(String logs) {
        if (logs == null || logs.isEmpty()) {
            return false;
        }
        String[] lines = logs.split("\n", -1);
        boolean trailingNewline = logs.endsWith("\n");
        int limit = trailingNewline ? lines.length - 1 : lines.length;
        boolean sawLine = false;
        for (int i = 0; i < limit; i++) {
            if (lines[i].isEmpty()) {
                continue;
            }
            sawLine = true;
            if (!hasDockerTimestampPrefix(lines[i])) {
                return false;
            }
        }
        return sawLine;
    }

    /**
     * 去掉每行开头的一层 Docker 时间戳（带时区的 RFC3339，后面紧跟一个空格）。
     * 没有空格、或行首时间不带时区时视为日志正文，整行保留。只剥一层。
     */
    public static String stripDockerTimestampPrefixes(String logs) {
        if (logs == null || logs.isEmpty()) {
            return logs == null ? "" : logs;
        }
        String[] lines = logs.split("\n", -1);
        boolean trailingNewline = logs.endsWith("\n");
        int limit = trailingNewline ? lines.length - 1 : lines.length;
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            if (kept.length() > 0) {
                kept.append('\n');
            }
            kept.append(stripOneDockerPrefix(lines[i]));
        }
        if (kept.length() > 0 && trailingNewline) {
            kept.append('\n');
        }
        return kept.toString();
    }

    private static String stripOneDockerPrefix(String line) {
        if (!hasDockerTimestampPrefix(line)) {
            return line == null ? "" : line;
        }
        return line.substring(line.indexOf(' ') + 1);
    }

    private static boolean hasDockerTimestampPrefix(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        int space = line.indexOf(' ');
        if (space <= 0) {
            return false;
        }
        try {
            OffsetDateTime.parse(line.substring(0, space));
            return true;
        } catch (DateTimeException ex) {
            return false;
        }
    }

    private static boolean isBeforeSince(String line, OffsetDateTime since) {
        OffsetDateTime timestamp = leadingTimestamp(line);
        return timestamp != null && timestamp.isBefore(since);
    }

    private static OffsetDateTime leadingTimestamp(String line) {
        if (line == null || line.isEmpty() || Character.isWhitespace(line.charAt(0))) {
            return null;
        }
        int end = 0;
        while (end < line.length() && !Character.isWhitespace(line.charAt(end))) {
            end++;
        }
        String token = line.substring(0, end);
        try {
            return OffsetDateTime.parse(token);
        } catch (DateTimeException ignored) {
            // 行首不是带偏移的时间
        }
        try {
            return LocalDateTime.parse(token).atZone(ZoneId.systemDefault()).toOffsetDateTime();
        } catch (DateTimeException ignored) {
            return null;
        }
    }
}
