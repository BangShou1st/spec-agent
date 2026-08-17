package com.specagent.agent;

/**
 * Raised when a model adapter cannot honor an agent contract, for example when
 * a task type is not supportable by the active adapter.
 */
public class ModelContractException extends RuntimeException {
    public ModelContractException(String message) {
        super(message);
    }
}