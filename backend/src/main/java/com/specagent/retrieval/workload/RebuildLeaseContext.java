package com.specagent.retrieval.workload;

/** Host-only context for a bounded unit of derived computation; never supplied by Python. */
public final class RebuildLeaseContext {
    private static final ThreadLocal<RebuildLeaseCoordinator.Token> CURRENT=new ThreadLocal<>();
    private RebuildLeaseContext() {}
    public static RebuildLeaseCoordinator.Token current() {return CURRENT.get();}
    public static Scope bind(RebuildLeaseCoordinator.Token token) {
        var previous=CURRENT.get();CURRENT.set(token);
        return ()->{if(previous==null) CURRENT.remove();else CURRENT.set(previous);};
    }
    public interface Scope extends AutoCloseable {@Override void close();}
}
