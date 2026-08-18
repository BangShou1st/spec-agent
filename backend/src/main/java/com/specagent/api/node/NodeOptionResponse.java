package com.specagent.api.node;

import com.specagent.node.NodeOption;

import java.util.UUID;

/**
 * Read-only representation of a selectable option on a node.
 *
 * <p>Option ids are runtime-owned and returned read-only. No Phase 6.1 API
 * allows a client to supply a {@code NodeOption} id for creation.
 */
public record NodeOptionResponse(
        UUID id,
        String label,
        String impact) {

    public static NodeOptionResponse from(NodeOption option) {
        return new NodeOptionResponse(option.id(), option.label(), option.impact());
    }
}