package com.specagent.agent.contract;

import java.util.UUID;

/**
 * A graph node as seen by the decision engine. The kind is the stable outer
 * classification (KNOWLEDGE / INTERACTION / RESOURCE / ARTIFACT); subtypes
 * and payloads live in the body/content, never in new action families.
 */
public record NodeView(UUID id, NodeBodyView body, String kind) {
}
