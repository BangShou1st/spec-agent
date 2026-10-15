package com.specagent.runtime.execution;

/** A bounded execution-scope wait failed before model execution or protected writes. */
public class ExecutionScopeUnavailableException extends RuntimeException {
    public ExecutionScopeUnavailableException() { super("Execution scope is temporarily unavailable"); }
}
