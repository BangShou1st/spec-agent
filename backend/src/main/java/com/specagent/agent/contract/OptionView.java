package com.specagent.agent.contract;

import java.util.UUID;

/** Runtime-owned option identity plus its display label. */
public record OptionView(UUID id, String label) {
}
