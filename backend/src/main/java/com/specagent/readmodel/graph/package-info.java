/**
 * Read-model boundary for the graph workspace read.
 *
 * <p>Composes existing runtime reads ({@code ProjectService},
 * {@code RouteService}, {@code NodeService}, {@code AnswerService}) into one
 * canonical project graph projection without depending on the HTTP API layer
 * and without making the API depend on runtime internals. Read-model services
 * are read-only: they never persist an answer, patch, node, route, or spec,
 * and they never call a model, provider, credential, or ContextBuilder.
 */
package com.specagent.readmodel.graph;
