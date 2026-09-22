package com.specagent.node;

/**
 * Narrow outbound port for rebuildable projections interested in Node writes.
 * The Node domain owns this interface so the retrieval package cannot create a
 * dependency cycle back into the canonical write path.
 */
public interface NodeIndexPort {

    void index(Node node);
}
