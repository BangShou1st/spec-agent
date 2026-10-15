package com.specagent.agent.runtime;

import java.util.UUID;

/** A routing hint only; authorization always reloads the immutable delegation and live grants. */
public final class WorkspaceExecutionContext {
    private static final ThreadLocal<UUID> RUN=new ThreadLocal<>();
    private WorkspaceExecutionContext() {}
    public static UUID currentRun() {return RUN.get();}
    public static void runWith(UUID run,Runnable action) {
        UUID previous=RUN.get();RUN.set(java.util.Objects.requireNonNull(run));
        try {action.run();}
        finally {if(previous==null) RUN.remove();else RUN.set(previous);}
    }
}
