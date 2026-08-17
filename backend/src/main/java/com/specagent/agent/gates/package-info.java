/**
 * Reflection gates: deterministic runtime validators applied to agent drafts
 * and context before anything is persisted. They never call a model and never
 * mutate state; they only accept or reject with reasons.
 */
package com.specagent.agent.gates;
