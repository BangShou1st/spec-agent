package com.specagent.agent.contract;

import java.util.List;

/**
 * Sanitized model-usage accounting returned by the brain. Hashes only — never
 * prompt text, provider payloads, or credentials.
 */
public record UsageView(int modelCalls, List<String> promptHashes) {

    public UsageView {
        promptHashes = promptHashes == null ? List.of() : List.copyOf(promptHashes);
    }
}
