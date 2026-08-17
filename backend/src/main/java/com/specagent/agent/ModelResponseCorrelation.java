package com.specagent.agent;

import java.util.Objects;

/**
 * Runtime-owned correlation validation between a {@link ModelRequest} and the
 * {@link ModelResponse} a gateway returned for it.
 *
 * <p>Gateway output is always untrusted input. Before any typed parsing,
 * reflection or persistence may happen, the runtime must verify that the
 * response echoes back the exact requesting agentRunId, contextSnapshotId and
 * taskType. Any mismatch rejects the response outright: the containing run is
 * failed and no artifact derived from that response may be persisted.
 */
public final class ModelResponseCorrelation {

    private ModelResponseCorrelation() {
    }

    /**
     * Validates that the response was produced for exactly this request.
     *
     * @throws ModelContractException on any correlation mismatch
     */
    public static void validate(ModelRequest request, ModelResponse response) {
        if (!Objects.equals(response.requestAgentRunId(), request.agentRunId())) {
            throw new ModelContractException(
                    "Model response agentRunId does not match the request agentRunId");
        }
        if (!Objects.equals(response.requestContextSnapshotId(), request.contextSnapshotId())) {
            throw new ModelContractException(
                    "Model response contextSnapshotId does not match the request contextSnapshotId");
        }
        if (response.taskType() != request.taskType()) {
            throw new ModelContractException(
                    "Model response taskType does not match the request taskType");
        }
    }
}