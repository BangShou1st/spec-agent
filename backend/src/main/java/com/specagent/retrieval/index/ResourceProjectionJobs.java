package com.specagent.retrieval.index;

import com.specagent.workspace.node.Node;
import java.util.Map;
import java.util.UUID;

/** Projection-owned port for bounded Python resource splitting jobs. */
public interface ResourceProjectionJobs {
    void invalidate(UUID nodeId);
    void enqueue(Node node, Map<String, Object> metadata);
}
