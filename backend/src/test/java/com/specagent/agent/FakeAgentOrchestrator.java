package com.specagent.agent;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Test-only compatibility facade over the remaining legacy orchestrator flow
 * (spec generation). Question drafting moved to the decision runtime; drive
 * it with {@link DecisionCycleTestDriver} instead.
 */
@Component
public class FakeAgentOrchestrator {

    private final AgentOrchestrator delegate;

    FakeAgentOrchestrator(AgentOrchestrator delegate) {
        this.delegate = delegate;
    }

    public FakeSpecRunResult generateSpec(UUID projectId) {
        SpecRunResult result = delegate.generateSpec(projectId);
        return new FakeSpecRunResult(result.run(), result.contextSnapshot(),
                result.modelResponse(), result.specSnapshot());
    }
}
