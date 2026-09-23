package com.specagent.assistant.runtime;

import java.util.UUID;

/** Published after any run reaches terminal state. Drives backend-owned handoff. */
public record RunTerminalEvent(UUID threadId, UUID runId, String terminalStatus) {
}
