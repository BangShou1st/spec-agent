package com.specagent.model.provider;

import java.util.UUID;

/**
 * Provider-side OpenCode Zen session identities.
 *
 * <p>Transport metadata only: never conversation memory, authorization,
 * WorkingState, a database field or prompt context. Production completions
 * derive a stable session from the provider-neutral run correlation id so
 * one run always reuses one session; credential probes and model discovery
 * use single-use ephemeral sessions.
 */
public final class OpenCodeZenSessionIds {

    private OpenCodeZenSessionIds() {
    }

    /**
     * Stable session for one inference run: {@code ses_} plus the run UUID
     * without hyphens. A null run id fails closed.
     */
    public static String forRun(UUID runId) {
        if (runId == null) {
            throw new IllegalArgumentException("Zen session run id must not be null");
        }
        return "ses_" + runId.toString().replace("-", "");
    }

    /**
     * Single-use session for run-less requests (credential probes, model
     * discovery): non-blank, single-line, bounded, header-safe.
     */
    public static String newEphemeral() {
        return "ses_" + UUID.randomUUID().toString().replace("-", "");
    }
}
