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
 * 任务日志的 tail / since 校验，以及按行首时间戳做尽力过滤。
 * DockerService 只有 tail，没有 since 参数，因此无法在 Docker 侧截断；
 * 行首能解析成 ISO-8601 的日志会按 since 丢掉更早的行，没有时间戳的行原样保留。
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
