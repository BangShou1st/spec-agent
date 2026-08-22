package com.specagent.agent.contract;

import java.util.UUID;

/**
 * Model-facing claim view: content and provenance only. Runtime-owned claim
 * ids are never part of the wire contract; a response that carries a claim id
 * is rejected fail-closed.
 */
public record ClaimView(String kind,
                        String text,
                        String status,
                        Double confidence,
                        UUID sourceNodeId,
                        UUID sourceAnswerId) {
}
