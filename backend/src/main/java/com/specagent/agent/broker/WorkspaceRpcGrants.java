package com.specagent.agent.broker;

import com.specagent.access.ActorContext;
import java.time.Duration;
import java.util.UUID;

/** Host-issued, purpose-limited delegation. Shared service authentication alone is insufficient. */
public interface WorkspaceRpcGrants {
    String HEADER = "X-Spec-Agent-Execution-Grant";
    String VERSION = "workspace-rpc-grant.v1";
    record Ticket(String value) { @Override public String toString() {return "WorkspaceRpcTicket[redacted]";} }
    Ticket issue(UUID run,String callType,int maximumCalls,Duration lifetime);
    default Ticket issue(UUID run,String callType,int maximumCalls,Duration lifetime,
            com.specagent.agent.protocol.AgentInputSnapshot snapshot) {
        return issue(run,callType,maximumCalls,lifetime);
    }
    void revoke(Ticket ticket);
    Invocation admit(String ticket,UUID run,String callType,String requestHash);
    interface Invocation {
        ActorContext actor();
        void finish(boolean succeeded,Runnable eventWriter);
        void unknown();
    }
}
