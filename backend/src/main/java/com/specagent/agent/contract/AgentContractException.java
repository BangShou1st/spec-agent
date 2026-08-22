package com.specagent.agent.contract;

/**
 * Raised when a cross-language contract violation is detected on either
 * side of the Java ↔ Python boundary: unknown protocol version, unknown field,
 * runtime-owned identity invented by the model/brain, unallowed source
 * reference, or any other fail-closed rejection.
 */
public class AgentContractException extends com.specagent.agent.ModelContractException {

    public AgentContractException(String message) {
        super(message);
    }
}
