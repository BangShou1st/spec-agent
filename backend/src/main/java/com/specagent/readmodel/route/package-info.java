/**
 * Read-model boundary for UI-support reads.
 *
 * <p>Derives safe read views from existing runtime services without making
 * {@code com.specagent.api..} depend on runtime internals and without making
 * {@code com.specagent.readmodel..} depend on the HTTP API layer. Read-model
 * services are read-only: they never persist an answer, patch, node, route, or
 * spec, and they never call a model.
 */
package com.specagent.readmodel.route;
