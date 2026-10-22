package com.specagent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件名:LiveAbAttemptStopGuard.java
 *
 * 用途:P3-B live A/B 的"失败即停、绝不重跑"守卫。
 *
 * <p>冻结协议把若干情况声明为必须立即停止的条件(429、认证失败、上游 5xx /
 * 连接失败 / provider-unavailable、隐式重试、预算耗尽,以及模型契约错误),
 * 并明确要求<b>不得对同一次已停止的尝试再次发起独立请求</b>:否则一次失败
 * 的实验会被悄悄变成"重试到通过",冻结证据随之失效。上一轮 B 臂在首次
 * TIMEOUT 之后又被独立重跑一次,正是该规则被绕过的实例。
 *
 * <p>本守卫在<b>任何新请求之前</b>读取同一实验报告路径上已有的报告:只要它记录了
 * 任一停止标志,就拒绝启动并给出明确原因(fail-closed)。它不读取报告内容以外的
 * 任何数据,不修改任何报告,也不改变任何评分或契约。
 */
public final class LiveAbAttemptStopGuard {

    /** 报告中一旦为 true 即表示"必须停止且不得重跑"的顶层布尔标志。 */
    static final List<String> STOP_FLAGS = List.of(
            "stopped_after_rate_limit",
            "stopped_after_provider_failure",
            "stopped_after_provider_retry",
            "stopped_after_unclassified_failure",
            "stopped_after_model_contract_failure",
            "stopped_on_request_budget");

    private LiveAbAttemptStopGuard() {
    }

    /**
     * 在发起任何新请求之前校验:同一报告路径上不存在记录了停止条件的既往报告。
     *
     * @param reportPath 本次尝试要写入的报告路径;不存在时视为可运行
     * @param mapper     读取既往报告用的 ObjectMapper
     * @throws IllegalStateException 既往报告记录了停止标志,或既往报告不可读
     */
    public static void requireNoPriorStop(Path reportPath, ObjectMapper mapper) {
        if (reportPath == null || !Files.isRegularFile(reportPath)) {
            return;
        }
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(reportPath));
        } catch (IOException | RuntimeException unreadable) {
            // 读不出来就无法排除"上一次已经停止"——按失败关闭处理。
            throw new IllegalStateException(
                    "refusing to start a new live attempt: the existing report at " + reportPath
                            + " cannot be read, so a previous stop condition cannot be ruled out",
                    unreadable);
        }
        List<String> flags = recordedStopFlags(root);
        if (!flags.isEmpty()) {
            throw new IllegalStateException(
                    "refusing to start a new live attempt: the report at " + reportPath
                            + " already recorded stop condition(s) " + flags
                            + "; the frozen protocol forbids rerunning a stopped attempt."
                            + " Use a fresh report path (and a fresh experiment id) for a new run.");
        }
    }

    /** 纯判定:返回该报告中记录的停止标志(空表示既往尝试没有触发停止条件)。 */
    public static List<String> recordedStopFlags(JsonNode report) {
        if (report == null || !report.isObject()) {
            return List.of();
        }
        List<String> flags = new ArrayList<>();
        for (String flag : STOP_FLAGS) {
            JsonNode value = report.get(flag);
            if (value != null && value.isBoolean() && value.booleanValue()) {
                flags.add(flag);
            }
        }
        return List.copyOf(flags);
    }
}
