package com.specagent.globalassistant.runtime;
import com.specagent.globalassistant.conversation.GlobalAssistantRunStatus;
/**
 * A run already has an execution owner or a terminal state: the second
 * dispatcher must stop without emitting contradictory events.
 */
public class GlobalAssistantRunClaimedException extends RuntimeException {
    private final GlobalAssistantRunStatus status;
    public GlobalAssistantRunClaimedException(GlobalAssistantRunStatus status) {
        super("Run is not available for execution: " + status);
        this.status = status;
    }
    public GlobalAssistantRunStatus status() {
        return status;
    }
}
