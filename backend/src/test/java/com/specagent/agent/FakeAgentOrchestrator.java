package com.specagent.agent;

import org.springframework.stereotype.Component;

import java.util.UUID;

/** Test-only compatibility facade; deterministic behavior stays at ModelGateway. */
@Component
public class FakeAgentOrchestrator {

    private final AgentOrchestrator delegate;

    FakeAgentOrchestrator(AgentOrchestrator delegate) {
        this.delegate = delegate;
    }

    public FakeAgentRunResult draftNextQuestion(UUID projectId) {
        AgentRunResult result = delegate.draftNextQuestion(projectId);
        return new FakeAgentRunResult(result.run(), result.contextSnapshot(),
                result.modelResponse(), result.producedNode());
    }

    public FakeSpecRunResult generateSpec(UUID projectId) {
        SpecRunResult result = delegate.generateSpec(projectId);
        return new FakeSpecRunResult(result.run(), result.contextSnapshot(),
                result.modelResponse(), result.specSnapshot());
    }
}
