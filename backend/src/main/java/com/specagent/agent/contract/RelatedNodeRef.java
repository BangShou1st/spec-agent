package com.specagent.agent.contract;

import java.util.UUID;

/**
 * A related canonical node in the bounded 1-hop semantic context, with explicit
 * provenance: the relation type and the direction relative to the anchor
 * ({@code OUTGOING} when the anchor is the relation source, {@code INCOMING}
 * when it is the target). Symmetric relations are canonicalized at write time,
 * so an INCOMING direction simply means the stored endpoints place the anchor
 * on the target side.
 */
public record RelatedNodeRef(UUID nodeId, String relationType, String direction) {
}
