package com.specagent.access;

/** Request-only browser session identity; never propagated into task execution. */
public final class BrowserSessionContext {
    private static final ThreadLocal<String> ID=new ThreadLocal<>();
    private BrowserSessionContext() {}
    public static String current() { return ID.get(); }
    public static ActorContextHolder.Scope bind(String id) {
        String previous=ID.get();
        if (id==null) ID.remove(); else ID.set(id);
        return () -> { if (previous==null) ID.remove(); else ID.set(previous); };
    }
}
