/**
 * Capability foundation: a generic adapter boundary between the agent's
 * {@code INVOKE_CAPABILITY} action and concrete implementations (internal
 * tools, Skill packages, MCP servers).
 *
 * <p>Descriptors are runtime-owned and permission-filtered before model
 * exposure; the planner never sees implementation classes. Capability
 * results are provenance-preserving observations that enter later bounded
 * decision cycles — never auto-confirmed graph truth. The runtime owns
 * retry/idempotency metadata through the durable invocation log.
 */
package com.specagent.capability;
