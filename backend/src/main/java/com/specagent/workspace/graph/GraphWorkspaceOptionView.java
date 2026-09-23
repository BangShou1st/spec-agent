package com.specagent.workspace.graph;

import com.specagent.workspace.node.NodeOption;

import java.util.UUID;

/**
 * Read-only option view inside a graph node.
 *
 * <p>Option ids are runtime-owned and read-only. Clients never supply option
 * ids back to the runtime for creation; a replacement option is expressed only
 * by label and impact. {@code recommended} marks the model's context-based
 * suggestion — advice for the user, never a pre-selected answer.
 */
public record GraphWorkspaceOptionView(
        UUID id,
        String label,
        String impact,
        boolean recommended) {

    public static GraphWorkspaceOptionView from(NodeOption option) {
        return new GraphWorkspaceOptionView(option.id(), option.label(), option.impact(),
                option.recommended());
    }
}
