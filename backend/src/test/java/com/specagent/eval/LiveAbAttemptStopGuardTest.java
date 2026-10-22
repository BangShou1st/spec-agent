package com.specagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:LiveAbAttemptStopGuardTest.java
 *
 * 用途:P3-B live A/B"失败即停、绝不重跑"守卫的离线回归。全部离线:只读写
 * 临时目录中的 JSON,不启动 Spring、不接触数据库、不发出任何模型请求。
 */
class LiveAbAttemptStopGuardTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Path write(Path dir, String json) throws Exception {
        Path file = dir.resolve("report.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void absentPriorReportAllowsANewAttempt(@TempDir Path dir) {
        assertThatCode(() -> LiveAbAttemptStopGuard.requireNoPriorStop(
                dir.resolve("never-written.json"), mapper)).doesNotThrowAnyException();
    }

    @Test
    void completeReportWithoutStopFlagsAllowsANewAttempt(@TempDir Path dir) throws Exception {
        Path file = write(dir, """
                {"report_status":"COMPLETE","stopped_after_provider_failure":false,
                 "stopped_after_rate_limit":false,"stopped_on_request_budget":false}
                """);
        assertThatCode(() -> LiveAbAttemptStopGuard.requireNoPriorStop(file, mapper))
                .doesNotThrowAnyException();
    }

    @Test
    void priorProviderFailureRefusesToStartAgain(@TempDir Path dir) throws Exception {
        // 上一轮的真实形态:B 臂首次 TIMEOUT 后报告记录了 provider_failure。
        Path file = write(dir, """
                {"report_status":"INCOMPLETE","stopped_after_provider_failure":true,
                 "stopped_after_rate_limit":false}
                """);
        assertThatThrownBy(() -> LiveAbAttemptStopGuard.requireNoPriorStop(file, mapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stopped_after_provider_failure")
                .hasMessageContaining("forbids rerunning a stopped attempt");
    }

    @Test
    void anyRecordedStopFlagRefusesToStartAgain(@TempDir Path dir) throws Exception {
        for (String flag : LiveAbAttemptStopGuard.STOP_FLAGS) {
            Path file = write(dir, "{\"" + flag + "\":true}");
            assertThatThrownBy(() -> LiveAbAttemptStopGuard.requireNoPriorStop(file, mapper))
                    .as(flag)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(flag);
        }
    }

    @Test
    void unreadablePriorReportFailsClosed(@TempDir Path dir) throws Exception {
        Path file = write(dir, "this is not json");
        assertThatThrownBy(() -> LiveAbAttemptStopGuard.requireNoPriorStop(file, mapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be read");
    }

    @Test
    void recordedStopFlagsIgnoresAbsentFalseAndNonBooleanValues() throws Exception {
        var report = mapper.readTree("""
                {"report_status":"INCOMPLETE","stopped_after_provider_failure":false,
                 "stopped_after_rate_limit":"true","stopped_on_request_budget":1}
                """);
        assertThat(LiveAbAttemptStopGuard.recordedStopFlags(report)).isEmpty();
        assertThat(LiveAbAttemptStopGuard.recordedStopFlags(null)).isEmpty();
    }
}
