package com.specagent.agent.decision;

import com.specagent.agent.protocol.AgentRequestEnvelope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic, self-scoped fault plan for the in-JVM deterministic engine
 * ({@code spec.agent.brain.engine=fake}).
 *
 * <p><strong>Why it exists.</strong> Some product states are unreachable
 * through the public UI/API <em>by construction</em> — most importantly
 * "answer persisted, its STATE_UPDATE checkpoint missing". That state is what
 * makes {@code ANSWER_CYCLE_INCOMPLETE} fire, and it is exactly what the
 * historical-answer recovery UX exists for. The product deliberately refuses
 * to create it any more (the DRAFT path will not cross an unprocessed answer),
 * so a browser test cannot reach it through real user actions without one
 * deliberate, declared failure. This plan supplies that failure.
 *
 * <p><strong>What is faked and what is not.</strong> Only the STATE_UPDATE
 * outcome of the marked run is forced to fail. Everything durable afterwards —
 * the persisted Answer, the failed-run record, the gate refusal, the recovery
 * run, the produced AnswerPatch, the generated spec — is produced by the
 * ordinary runtime, unchanged.
 *
 * <p><strong>Scoping.</strong> Three independent limits keep it inert
 * everywhere else:
 * <ul>
 *   <li>registered only when the deterministic engine is selected; the normal
 *       product configuration ({@code remote-python}) never creates it;</li>
 *   <li>only a run whose submitted free text carries the directive is
 *       affected — every other run in the same JVM is untouched;</li>
 *   <li>the budget is per answered node and consumes exactly the declared
 *       number of calls, so "fails twice, then succeeds" is deterministic and
 *       no other test can consume it (no global counter, no timing).</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "spec.agent.brain.engine", havingValue = "fake")
public class DeterministicEngineFaultPlan {

    /**
     * Directive carried by the submitted answer text:
     * {@code [[fail-state-update:N]]} makes the next {@code N} STATE_UPDATE
     * calls for that answered node fail. It is ignored when absent or
     * malformed, so ordinary answer text is never misread.
     */
    public static final String DIRECTIVE_PREFIX = "[[fail-state-update:";
    public static final String DIRECTIVE_SUFFIX = "]]";

    private final Map<UUID, AtomicInteger> budgetsByNode = new ConcurrentHashMap<>();

    /**
     * Fails the current STATE_UPDATE on purpose when the answered node carries
     * an armed directive; returns normally otherwise.
     *
     * @throws IllegalStateException the declared, typed failure the caller
     *         must observe as a failed run — deliberately not a model-contract
     *         or brain-availability error, so it cannot be confused with a
     *         real provider outcome in run events or recovery copy
     */
    public void failStateUpdateIfDirected(AgentRequestEnvelope request) {
        if (request == null || request.event() == null) {
            return;
        }
        int declared = declaredBudget(request.event().freeText());
        if (declared <= 0) {
            return;
        }
        UUID nodeId = request.event().anchorNodeId();
        if (nodeId == null) {
            return;
        }
        AtomicInteger remaining = budgetsByNode.computeIfAbsent(
                nodeId, key -> new AtomicInteger(declared));
        int current = remaining.get();
        while (current > 0) {
            if (remaining.compareAndSet(current, current - 1)) {
                throw new IllegalStateException(
                        "DETERMINISTIC_ENGINE_FAULT: STATE_UPDATE failed on purpose for node "
                                + nodeId + " (" + current + " of " + declared
                                + " declared failures remaining)");
            }
            current = remaining.get();
        }
    }

    /** Remaining declared failures for one node; exposed for test assertions. */
    public int remainingFailures(UUID nodeId) {
        AtomicInteger remaining = budgetsByNode.get(nodeId);
        return remaining == null ? 0 : remaining.get();
    }

    private static int declaredBudget(String freeText) {
        if (freeText == null) {
            return 0;
        }
        int start = freeText.indexOf(DIRECTIVE_PREFIX);
        if (start < 0) {
            return 0;
        }
        int valueStart = start + DIRECTIVE_PREFIX.length();
        int end = freeText.indexOf(DIRECTIVE_SUFFIX, valueStart);
        if (end < 0) {
            return 0;
        }
        try {
            return Math.max(0, Math.min(10, Integer.parseInt(
                    freeText.substring(valueStart, end).strip())));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
