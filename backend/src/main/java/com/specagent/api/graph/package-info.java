/**
 * Read-only graph workspace API.
 *
 * <p>Exposes the canonical project graph for the Phase 7.3 workspace. The
 * controller stays thin and delegates to the graph read model; it never
 * touches repositories, model/provider/credential packages, or
 * {@code ContextBuilder}, and it never exposes patches, context snapshots,
 * provider payloads, credentials, AgentRun traces, or raw DB metadata.
 */
package com.specagent.api.graph;
