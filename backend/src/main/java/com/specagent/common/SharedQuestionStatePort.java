package com.specagent.common;

import java.util.UUID;

/**
 * Port through which the answer package asks the graph package to validate the
 * project-wide shared state of a canonical Question node before finalizing an
 * Answer.
 *
 * <p>The {@code SHARED_STATE_DIVERGENCE} rule is a graph invariant: a canonical
 * Question carries exactly one immutable Answer identity project-wide, so
 * finalizing a second Answer on the same node must fail. The rule's owner is
 * {@code graph.GraphInvariantValidator}, but the writer that triggers it is
 * {@code answer.AnswerService}. Declaring the seam here keeps the dependency
 * one-way ({@code graph -> answer} only, for the answer-existence read) and
 * therefore removes the {@code answer <-> graph} package cycle. The
 * implementation lives in the graph package and delegates to the existing
 * validator method unchanged — no rule logic moves.
 *
 * <p>The signature is intentionally primitive-only (project id + node id), so
 * this shared kernel package stays free of domain types and of any dependency
 * of its own.
 */
public interface SharedQuestionStatePort {

    /**
     * Fails closed when the canonical node already carries an immutable Answer
     * identity, so the caller must not persist a second one.
     *
     * @throws IllegalStateException with the {@code SHARED_STATE_DIVERGENCE} code
     */
    void validateSharedQuestionState(UUID projectId, UUID nodeId);
}
