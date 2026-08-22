package com.specagent.agent.contract;

/**
 * Closed set of generic action families a decision may propose.
 *
 * <p>The families are deliberately domain-neutral product mechanics. Business
 * abilities are added as payload semantics or capability descriptors, never as
 * new action names. Stage A validates membership and payload shape only; no
 * family is executed by the Stage A worker.
 */
public enum ActionFamily {
    CREATE_NODE,
    UPDATE_NODE,
    CONNECT_NODE,
    CREATE_ROUTE,
    REQUEST_USER_INPUT,
    RESPOND_TO_USER,
    INVOKE_CAPABILITY,
    GENERATE_ARTIFACT,
    WAIT;

    public String code() {
        return name();
    }

    public static ActionFamily fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Action family must not be blank");
        }
        try {
            return ActionFamily.valueOf(code);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown action family: " + code);
        }
    }
}
