/**
 * Graph workspace command layer: transactional graph mutation commands,
 * semantic relations, and the typed operation log backing Undo/Redo.
 *
 * <p>Commands own graph invariants (append-preserving lineage, explicit
 * branch on non-tip continuation, immutable answers) and append one typed
 * {@link com.specagent.graph.GraphOperation} per durable mutation. No
 * business-specific node commands live here; external agent action families
 * map onto these commands after policy approval.
 */
package com.specagent.graph;
