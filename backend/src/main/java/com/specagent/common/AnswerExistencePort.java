package com.specagent.common;

import java.util.UUID;

/**
 * Port through which the graph package asks the one question it needs about
 * answer state: does this canonical node already carry an immutable Answer
 * identity?
 *
 * <p>Answer state is answer-owned, but the graph write-time validators and the
 * undo/redo preconditions must read it (a Question with a finalized Answer may
 * neither gain a second Answer nor be retracted). Declaring the seam here —
 * instead of letting {@code GraphInvariantValidator} and {@code UndoRedoService}
 * depend on {@code answer.AnswerRepository} directly — keeps the dependency
 * one-way ({@code answer -> graph} only, because {@code AnswerService} consumes
 * the graph validation seam) and therefore removes the {@code answer <-> graph}
 * package cycle. The implementation lives in the answer package and delegates
 * to the existing repository statement unchanged.
 *
 * <p>The signature is intentionally primitive-only (a node id in, a boolean
 * out), so this shared kernel package stays free of domain types and of any
 * dependency of its own.
 */
public interface AnswerExistencePort {

    /** True when the canonical node already has a finalized immutable Answer. */
    boolean nodeHasFinalizedAnswer(UUID nodeId);
}
