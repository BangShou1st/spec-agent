package com.specagent.readmodel.graph;

import com.specagent.node.NodeOption;

import java.util.UUID;

/**
 * Read-only option view inside a graph node.
 *
 * <p>Option ids are runtime-owned and read-only. Clients never supply option
 * ids back to the runtime for creation; a replacement option is expressed only
 * by label and impact.
 */
public record GraphWorkspaceOptionView(
        UUID id,
        String label,
        String impact) {

    public static GraphWorkspaceOptionView from(NodeOption option) {
        return new GraphWorkspaceOptionView(option.id(), option.label(), option.impact());
    }
}
