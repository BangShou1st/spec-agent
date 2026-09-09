package com.specagent.globalassistant.runtime;

import org.springframework.stereotype.Component;

/**
 * Bounded loop budgets. Normal runs use 0-3 tool calls.
 */
@Component
public class GlobalAssistantRuntimeProperties {
    public int maxSteps() {
        return 6;
    }
    public int maxToolCalls() {
        return 5;
    }
}
