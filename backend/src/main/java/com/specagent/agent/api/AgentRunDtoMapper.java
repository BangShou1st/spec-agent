package com.specagent.agent.api;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runevent.RunProgressAssembler;
import com.specagent.agent.runevent.RunProgressView;
import com.specagent.common.Json;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 文件名:AgentRunDtoMapper.java
 *
 * 用途:把 {@link AgentRun} 映射为安全的 API 表示,包括脱敏后的
 * trace 步骤列表和经白名单过滤的 run 进度视图。
 *
 * trace 以 JSON 字符串形式存于 JSONB 列,读回来的值是一个 JSON 字符串
 * 字面量(带外层引号、换行被转义),这里会解码回普通换行拼接的生命周期
 * 步骤。trace 刻意只包含诊断性的生命周期步骤,绝不包含原始 provider
 * 载荷或机密信息。
 *
 * 协作:被 AgentRunController 用于构造响应 DTO。
 */
@Component
public class AgentRunDtoMapper {

    private final Json json;
    private final RunProgressAssembler runProgressAssembler;

    public AgentRunDtoMapper(Json json, RunProgressAssembler runProgressAssembler) {
        this.json = json;
        this.runProgressAssembler = runProgressAssembler;
    }

    public AgentRunResponse from(AgentRun run) {
        RunProgressView progress = runProgressAssembler.assemble(run.id());
        return AgentRunResponse.from(run, traceSteps(run.trace()), progress);
    }

    private List<String> traceSteps(String rawTrace) {
        if (rawTrace == null || rawTrace.isBlank() || "null".equals(rawTrace)) {
            return List.of();
        }
        String trace = rawTrace;
        if (rawTrace.startsWith("\"")) {
            trace = json.read(rawTrace, String.class);
        }
        if (trace == null || trace.isBlank()) {
            return List.of();
        }
        return Arrays.stream(trace.split("\n"))
                .map(String::trim)
                .filter(step -> !step.isEmpty())
                .toList();
    }
}