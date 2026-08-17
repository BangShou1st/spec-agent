package com.specagent.model.gateway;

import com.specagent.agent.ModelRequest;
import com.specagent.agent.ModelResponse;

/**
 * The only boundary through which the agent loop talks to a model.
 *
 * <p>The runtime owns correlation validation: every {@link ModelResponse}
 * returned by a gateway is untrusted input and must echo back the requesting
 * agentRunId, contextSnapshotId and taskType before any typed parsing or
 * persistence may happen. Gateways never touch the database and never persist
 * anything themselves.
 *
 * <p>A gateway is provider-neutral. Provider-specific HTTP details live behind
 * {@code OpenCodeZenTransport}; concrete providers implement this interface
 * (for example {@link FakeModelAdapter} or {@code OpenCodeZenModelGateway}).
 */
public interface ModelGateway {

    ModelResponse run(ModelRequest request);
}