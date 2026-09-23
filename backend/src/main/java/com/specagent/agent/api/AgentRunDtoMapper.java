package com.specagent.agent.api;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runevent.RunProgressAssembler;
import com.specagent.agent.runevent.RunProgressView;
import com.specagent.common.Json;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Maps {@link AgentRun} to its safe API representation, including the
 * sanitized trace-step list and the whitelisted run progress view.
 *
 * <p>The trace is stored as a JSON string through a JSONB column, so the
 * read-back value is a JSON string literal (outer quotes, escaped newlines).
 * It is decoded back to plain newline-joined lifecycle steps. The trace
 * intentionally contains diagnostic lifecycle steps only, never raw provider
 * payloads or secrets.
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