package com.sentinel.service;

import com.sentinel.config.BusinessException;
import com.sentinel.enums.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("容器日志窗口")
class ContainerLogWindowTest {

    @Test
    @DisplayName("tail 只接受 1 到 5000")
    void checksTailRange() {
        ContainerLogWindow.checkTail(1);
        ContainerLogWindow.checkTail(100);
        ContainerLogWindow.checkTail(5000);

        assertThatThrownBy(() -> ContainerLogWindow.checkTail(0))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.VALIDATION_ERROR.getCode())
                .hasMessage(ContainerLogWindow.TAIL_RANGE_MESSAGE);
        assertThatThrownBy(() -> ContainerLogWindow.checkTail(5001))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ContainerLogWindow.TAIL_RANGE_MESSAGE);
    }

    @Test
    @DisplayName("since 接受带偏移和无时区的 ISO-8601，拒绝纯日期")
    void parsesSince() {
        assertThat(ContainerLogWindow.parseSince(null)).isNull();
        assertThat(ContainerLogWindow.parseSince("  ")).isNull();
        assertThat(ContainerLogWindow.parseSince("2026-10-09T08:00:00Z"))
                .isEqualTo(OffsetDateTime.parse("2026-10-09T08:00:00Z"));
        assertThat(ContainerLogWindow.parseSince("2026-10-09T08:00:00.123456789+08:00"))
                .isEqualTo(OffsetDateTime.parse("2026-10-09T08:00:00.123456789+08:00"));
        assertThat(ContainerLogWindow.parseSince("2026-10-09T08:00:00"))
                .isEqualTo(LocalDateTime.parse("2026-10-09T08:00:00")
                        .atZone(ZoneId.systemDefault())
                        .toOffsetDateTime());

        assertThatThrownBy(() -> ContainerLogWindow.parseSince("2026-10-09"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.VALIDATION_ERROR.getCode())
                .hasMessage(ContainerLogWindow.SINCE_FORMAT_MESSAGE);
        assertThatThrownBy(() -> ContainerLogWindow.parseSince("not-a-date"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ContainerLogWindow.SINCE_FORMAT_MESSAGE);
    }

    @Test
    @DisplayName("行数忽略末尾换行")
    void countsLines() {
        assertThat(ContainerLogWindow.countLines(null)).isZero();
        assertThat(ContainerLogWindow.countLines("")).isZero();
        assertThat(ContainerLogWindow.countLines("\n")).isZero();
        assertThat(ContainerLogWindow.countLines("a")).isEqualTo(1);
        assertThat(ContainerLogWindow.countLines("a\n")).isEqualTo(1);
        assertThat(ContainerLogWindow.countLines("a\nb\n")).isEqualTo(2);
        assertThat(ContainerLogWindow.countLines("a\n\nb")).isEqualTo(3);
    }

    @Test
    @DisplayName("没有时间戳的行保留，早于 since 的时间戳行丢掉")
    void filtersByLeadingTimestamp() {
        OffsetDateTime since = OffsetDateTime.parse("2026-10-09T08:00:00Z");
        String logs = """
                2026-10-09T07:00:00.000000000Z old
                plain line
                2026-10-09T08:00:00Z same
                2026-10-09T09:00:00.000000000Z new
                """;

        assertThat(ContainerLogWindow.filterSince(logs, since)).isEqualTo("""
                plain line
                2026-10-09T08:00:00Z same
                2026-10-09T09:00:00.000000000Z new
                """);
        assertThat(ContainerLogWindow.filterSince(logs, null)).isEqualTo(logs);
        assertThat(ContainerLogWindow.filterSince(null, since)).isEmpty();
    }

    @Test
    @DisplayName("无时区的行首时间按系统时区比较")
    void filtersLocalTimestampsWithSystemZone() {
        OffsetDateTime since = LocalDateTime.parse("2026-10-09T08:00:00")
                .atZone(ZoneId.systemDefault())
                .toOffsetDateTime();

        String filtered = ContainerLogWindow.filterSince(
                "2026-10-09T07:00:00 early\n2026-10-09T08:00:00 same\n", since);

        assertThat(filtered).isEqualTo("2026-10-09T08:00:00 same\n");
    }
}
