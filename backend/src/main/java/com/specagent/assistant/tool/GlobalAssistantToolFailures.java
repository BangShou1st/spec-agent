package com.specagent.assistant.tool;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
/**
 * 文件名:GlobalAssistantToolFailures.java
 *
 * 用途:工具能力失败结果的统一构造器——把错误码与有界的原因文本
 * 组装成标准形状的 FAILED CapabilityResult,保证失败载荷可控、可预期。
 */
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
