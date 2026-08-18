/**
 * Read-model boundary for UI-support reads.
 *
 * <p>Derives safe read views from existing runtime/context services without
 * making {@code com.specagent.api..} depend on {@code com.specagent.context..}.
 * Read-model services are read-only: they never persist an answer, patch, node,
 * route, or spec, and they never call a model.
 */
package com.specagent.readmodel.requirement;