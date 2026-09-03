package com.specagent.eval;

import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.decision.RemotePythonDecisionEngine;
import com.specagent.model.inference.ModelInferenceGateway;
import com.specagent.model.inference.OpenCodeModelInferenceGateway;

/**
 * Fail-fast identity guard for the B-live evaluation profile.
 *
 * <p>A live label is meaningful only when the Java boundary reaches the
 * remote Python brain and the Java broker reaches the real OpenCode gateway.
 * The guard deliberately checks concrete production bean identities at the
 * evaluation boundary: a test-only scripted brain or fake inference gateway
 * must never be able to masquerade as a live-provider observation.
 */
public final class LiveExecutionGuard {

    private LiveExecutionGuard() {
    }

    public static Evidence requireRemoteProvider(AgentDecisionEngine decisionEngine,
                                                  ModelInferenceGateway inferenceGateway,
                                                  BrainScriptInstaller scriptedBrain) {
        if (scriptedBrain != null) {
            throw new IllegalStateException(
                    "B-live rejected: ScriptedBrain/BrainScriptInstaller is active");
        }
        if (!(decisionEngine instanceof RemotePythonDecisionEngine)) {
            throw new IllegalStateException(
                    "B-live rejected: AgentDecisionEngine is "
                            + typeName(decisionEngine)
                            + "; expected RemotePythonDecisionEngine");
        }
        if (!(inferenceGateway instanceof OpenCodeModelInferenceGateway)) {
            throw new IllegalStateException(
                    "B-live rejected: ModelInferenceGateway is "
                            + typeName(inferenceGateway)
                            + "; expected OpenCodeModelInferenceGateway"
                            + " (fake providers are not live)");
        }
        return new Evidence(
                decisionEngine.getClass().getName(),
                inferenceGateway.getClass().getName());
    }

    private static String typeName(Object value) {
        return value == null ? "<none>" : value.getClass().getName();
    }

    /** Safe bean identity evidence; contains no credentials or prompt data. */
    public record Evidence(String decisionEngine, String inferenceGateway) {
    }
}
