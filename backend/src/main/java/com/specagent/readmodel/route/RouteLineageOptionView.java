package com.specagent.readmodel.route;

import com.specagent.node.NodeOption;

import java.util.UUID;

/**
 * Read-only option view inside a route lineage node.
 *
 * <p>Option ids are runtime-owned and read-only. Clients never supply option
 * ids back to the runtime for creation; a replacement option is expressed only
 * by label and impact.
 */
public record RouteLineageOptionView(
        UUID id,
        String label,
        String impact) {

    public static RouteLineageOptionView from(NodeOption option) {
        return new RouteLineageOptionView(option.id(), option.label(), option.impact());
    }
}
