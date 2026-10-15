package com.specagent.agent.snapshot;

/** The audit payload remains valid and immutable; its private sources cannot be reused now. */
public class FrozenSourceAuthorizationRefusedException extends RuntimeException {
    public FrozenSourceAuthorizationRefusedException() { super("FROZEN_INPUT_SOURCE_NOT_AUTHORIZED"); }
}
