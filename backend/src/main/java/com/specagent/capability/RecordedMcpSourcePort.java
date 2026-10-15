package com.specagent.capability;

import java.util.Map;
import java.util.UUID;

/** Neutral result-source contract. Agent does not depend on MCP implementation packages. */
public interface RecordedMcpSourcePort {
    record Reference(UUID rowId,long version,String connectionId,String toolName) {}
    boolean handles(String capability);
    Reference reference(String capability,Map<String,Object> provenance);
    void require(Reference reference);
    boolean visible(String capability,Map<String,Object> provenance);
}
