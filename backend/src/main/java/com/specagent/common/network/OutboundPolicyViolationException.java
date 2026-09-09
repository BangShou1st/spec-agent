package com.specagent.common.network;

/**
 * Typed failure when an outbound URL/host violates shared network policy.
 * Never exposes provider/stack details to callers.
 */
public class OutboundPolicyViolationException extends RuntimeException {

    public OutboundPolicyViolationException(String message) {
        super(message);
    }
}