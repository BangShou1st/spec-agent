package com.specagent.globalassistant.tool;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
public final class GlobalAssistantToolFailures {
    private GlobalAssistantToolFailures() {
    }
    public static CapabilityResult failed(CapabilityInvocation invocation, String capabilityId,
            String errorCode, String reason) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("errorCode", errorCode);
        String text = reason == null ? "" : reason;
        content.put("reason", text.length() <= 500 ? text : text.substring(0, 500));
        return new CapabilityResult(invocation.invocationId(), invocation.invocationKey(),
                capabilityId, CapabilityResult.Status.FAILED, content,
                List.of(), Map.of(), List.of());
    }
}
