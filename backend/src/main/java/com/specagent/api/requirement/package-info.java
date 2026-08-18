/**
 * Requirement-state API boundary: one read-only UI-support endpoint that
 * exposes the backend-derived requirement state for the project's active route.
 * The controller depends only on the read-model query boundary; it never
 * depends on {@code com.specagent.context..} and never writes state.
 */
package com.specagent.api.requirement;