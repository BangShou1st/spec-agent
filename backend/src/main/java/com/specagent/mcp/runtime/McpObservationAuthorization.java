package com.specagent.mcp.runtime;

import com.specagent.mcp.transport.McpTransportException;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Check stored result sources without contacting the MCP server or resolving a secret. */
@Component
public class McpObservationAuthorization implements com.specagent.capability.RecordedMcpSourcePort {
    private final McpConnectionLookupPort connections;
    public McpObservationAuthorization(McpConnectionLookupPort connections) {this.connections=connections;}
    public boolean handles(String capability) {return capability!=null && capability.startsWith("mcp.");}
    public Reference reference(String capability,Map<String,Object> provenance) {
        try {
            if(!handles(capability) || !"MCP_TOOL".equals(provenance.get("kind"))) throw refused();
            UUID row=UUID.fromString((String)provenance.get("connectionRowId"));
            Object raw=provenance.get("configurationVersion");
            if(!(raw instanceof Number number)) throw refused();
            long version=number.longValue();
            if(version<1 || number.doubleValue()!=(double)version) throw refused();
            String connection=(String)provenance.get("connectionId"),tool=(String)provenance.get("toolName");
            if(connection==null || tool==null || !capability.equals("mcp."+connection+"."+tool)) throw refused();
            return new Reference(row,version,connection,tool);
        } catch(RuntimeException ex) {throw refused();}
    }
    public void require(Reference reference) {
        var current=connections.lockVisibleResultSource(reference.rowId()).orElseThrow(McpObservationAuthorization::refused);
        if(!current.agentVisible() || current.configurationVersion()!=reference.version()
                || !current.connectionId().equals(reference.connectionId())) throw refused();
    }
    public boolean visible(String capability,Map<String,Object> provenance) {
        try {require(reference(capability,provenance));return true;}
        catch(McpTransportException ex) {return false;}
    }
    private static McpTransportException refused() {return new McpTransportException("MCP_RESULT_SOURCE_CHANGED_OR_INACCESSIBLE");}
}
