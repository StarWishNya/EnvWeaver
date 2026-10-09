package com.sentinel.service.provision;

import com.github.dockerjava.api.exception.DockerException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 Docker 异常收成一行原因，并去掉守护进程地址。
 */
public final class DockerMessages {

    static final int MAX_CAUSE_CHARS = 200;

    /**
     * unix://、tcp://、http(s)://，以及 host:port 形式的守护进程地址。
     */
    private static final Pattern DAEMON_ENDPOINT = Pattern.compile(
            "(?i)(?:(?:unix|tcp|https?)://\\S+"
                    + "|(?:\\d{1,3}(?:\\.\\d{1,3}){3}|localhost"
                    + "|[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*)"
                    + ":\\d{2,5})");

    /**
     * docker-java 在原因前加的 "Status 404: "。
     */
    private static final Pattern STATUS_PREFIX = Pattern.compile("^Status \\d{3}:\\s*");

    /**
     * 守护进程返回的 {"message":"..."} 外壳。
     */
    private static final Pattern JSON_MESSAGE = Pattern.compile("^\\{\\s*\"message\"\\s*:\\s*\"(.*)\"\\s*}$");

    private DockerMessages() {
    }

    /**
     * DockerException 的第一行原因。不是 Docker 异常时返回 null，避免把任意异常原文带出去。
     */
    public static String causeOf(Throwable error) {
        DockerException docker = findDocker(error);
        if (docker == null) {
            return null;
        }
        return sanitize(docker.getMessage());
    }

    /**
     * 只留第一行，去掉守护进程地址，最长 200 字符。空白结果返回 null。
     */
    public static String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String line = message;
        int breakAt = line.indexOf('\n');
        if (breakAt < 0) {
            breakAt = line.indexOf('\r');
        }
        if (breakAt >= 0) {
            line = line.substring(0, breakAt);
        }
        line = STATUS_PREFIX.matcher(line.trim()).replaceFirst("");
        Matcher json = JSON_MESSAGE.matcher(line.trim());
        if (json.matches()) {
            line = json.group(1).replace("\\\"", "\"");
        }
        line = DAEMON_ENDPOINT.matcher(line).replaceAll("");
        line = line.replaceAll("[\\t ]{2,}", " ").trim();
        if (line.isEmpty()) {
            return null;
        }
        if (line.length() > MAX_CAUSE_CHARS) {
            line = line.substring(0, MAX_CAUSE_CHARS).trim();
        }
        return line.isEmpty() ? null : line;
    }

    /**
     * 取最后 maxLines 行。空文本返回空字符串。
     */
    public static String tailLines(String text, int maxLines) {
        if (text == null || text.isBlank() || maxLines <= 0) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isEmpty()) {
            return "";
        }
        String[] lines = normalized.split("\n", -1);
        int start = Math.max(0, lines.length - maxLines);
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (i > start) {
                builder.append('\n');
            }
            builder.append(lines[i]);
        }
        return builder.toString();
    }

    private static DockerException findDocker(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof DockerException docker) {
                return docker;
            }
            current = current.getCause();
        }
        return null;
    }
}
