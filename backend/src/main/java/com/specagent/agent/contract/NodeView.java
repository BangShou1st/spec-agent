package com.specagent.agent.contract;

import java.util.UUID;

/** A graph node as seen by the decision engine. */
public record NodeView(UUID id, NodeBodyView body) {
}
