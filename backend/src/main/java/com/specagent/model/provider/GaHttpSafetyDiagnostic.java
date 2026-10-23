package com.specagent.model.provider;

import com.specagent.model.contract.GaModelContract;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sanitized diagnostic for GA HTTP model calls; strictly captures metadata,
 * message structure summaries, tool ID matching, and boolean signature flags.
 * NEVER records API keys, Authorization headers, raw requests, thought signature values,
 * user private text, or tool payload contents.
 */
public record GaHttpSafetyDiagnostic(
        String providerContext,
        String modelId,
        String endpointCategory,
        Integer upstreamHttpStatus,
        String errorCategory,
        String failurePhase,
        List<MessageSummary> messagesSummary,
        ToolCallIdRelation toolCallIdRelation,
        boolean hasThoughtSignature,
        UUID runId,
        UUID callId,
        String callType,
        int requestTurn
) {
    public record MessageSummary(
            String role,
            int contentLength,
            int toolCallsCount,
            boolean hasToolCallId,
            boolean hasThoughtSignature
    ) {}

    public record ToolCallIdRelation(
            List<String> assistantToolCallIds,
            List<String> toolResultIds,
            boolean allMatched,
            boolean hasOrphans,
            boolean hasUnanswered
    ) {}

    public static GaHttpSafetyDiagnostic capture(
            String providerContext,
            String modelId,
            String endpointCategory,
            Integer upstreamHttpStatus,
            String errorCategory,
            String failurePhase,
            GaModelContract.Request request
    ) {
        if (request == null) {
            return new GaHttpSafetyDiagnostic(providerContext, modelId, endpointCategory,
                    upstreamHttpStatus, errorCategory, failurePhase,
                    List.of(), new ToolCallIdRelation(List.of(), List.of(), true, false, false),
                    false, null, null, null, 1);
        }
        List<MessageSummary> summaries = new ArrayList<>();
        List<String> assistantToolIds = new ArrayList<>();
        List<String> toolResultIds = new ArrayList<>();
        boolean hasSig = false;
        boolean hasToolMessage = false;

        for (var msg : request.messages()) {
            boolean msgHasSig = false;
            for (var tc : msg.toolCalls()) {
                assistantToolIds.add(tc.id());
                if (tc.extraContent() != null && containsSignature(tc.extraContent())) {
                    msgHasSig = true;
                    hasSig = true;
                }
            }
            if ("tool".equals(msg.role())) {
                hasToolMessage = true;
                if (msg.toolCallId() != null) {
                    toolResultIds.add(msg.toolCallId());
                }
            }
            summaries.add(new MessageSummary(
                    msg.role(),
                    msg.content() == null ? 0 : msg.content().length(),
                    msg.toolCalls().size(),
                    msg.toolCallId() != null,
                    msgHasSig
            ));
        }

        boolean allMatched = assistantToolIds.size() == toolResultIds.size()
                && assistantToolIds.equals(toolResultIds);
        boolean hasOrphans = toolResultIds.stream().anyMatch(id -> !assistantToolIds.contains(id));
        boolean hasUnanswered = assistantToolIds.stream().anyMatch(id -> !toolResultIds.contains(id));

        int turn = hasToolMessage ? 2 : 1;
        String phase = failurePhase != null ? failurePhase : (turn == 2 ? "SECOND_CALL_AFTER_TOOL" : "INITIAL_PROMPT");

        return new GaHttpSafetyDiagnostic(
                providerContext,
                modelId,
                endpointCategory,
                upstreamHttpStatus,
                errorCategory,
                phase,
                List.copyOf(summaries),
                new ToolCallIdRelation(List.copyOf(assistantToolIds), List.copyOf(toolResultIds),
                        allMatched, hasOrphans, hasUnanswered),
                hasSig,
                request.runId(),
                request.callId(),
                request.callType(),
                turn
        );
    }

    private static boolean containsSignature(Map<String, Object> extra) {
        if (extra == null) return false;
        if (extra.containsKey("thought_signature") || extra.containsKey("thoughtSignature")) return true;
        for (var val : extra.values()) {
            if (val instanceof Map<?, ?> nested) {
                if (nested.containsKey("thought_signature") || nested.containsKey("thoughtSignature")) return true;
            }
        }
        return false;
    }
}
