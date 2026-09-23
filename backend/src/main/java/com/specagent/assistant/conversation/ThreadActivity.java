package com.specagent.assistant.conversation;

import com.specagent.assistant.conversation.GlobalAssistantRun;
import java.util.Optional;

/** Backend-owned thread activity truth. */
public record ThreadActivity(Optional<GlobalAssistantRun> activeRun, Optional<PendingTurn> pendingSteer) {
    public static ThreadActivity empty() {
        return new ThreadActivity(Optional.empty(), Optional.empty());
    }
}
