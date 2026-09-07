package com.specagent.agent.runevent;

/**
 * Shared durable run-event protocol for terminal-outcome signals.
 *
 * <p>Node-query execution, the continuation coordinator, answer/decision
 * cycles, and the query result view all read the same event rows. The string
 * values are frozen runtime evidence — never rename one without a migration
 * of existing rows.
 */
public final class AgentRunEventTypes {

    /** The cycle answered and emitted a user-visible message. */
    public static final String RESPOND_MESSAGE_EVENT = "RESPOND_MESSAGE";

    /** Policy denied the proposed action without durable change. */
    public static final String POLICY_DENIED_EVENT = "POLICY_DENIED";

    /** A mutation proposal could never become executable, so none was kept. */
    public static final String MUTATION_NOT_CONFIRMABLE_EVENT = "MUTATION_NOT_CONFIRMABLE";

    private AgentRunEventTypes() {
    }
}
