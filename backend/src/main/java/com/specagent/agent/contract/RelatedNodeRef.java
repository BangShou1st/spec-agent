package com.specagent.agent.contract;

import java.util.UUID;

/**
 * A related canonical node in the bounded 1-hop semantic context, with explicit
 * provenance: the relation type, the direction relative to the anchor
 * ({@code OUTGOING} when the anchor is the relation source, {@code INCOMING}
 * when it is the target), and the projected {@link NodeView}/{@link NodeBodyView}
 * of the related node itself so the model reads real body content, not only
 * opaque ids. Symmetric relations are canonicalized at write time, so an
 * INCOMING direction simply means the stored endpoints place the anchor on the
 * target side. Related nodes are never part of the lineage.
 */
public record RelatedNodeRef(UUID nodeId, String relationType, String direction, NodeView node) {
}
