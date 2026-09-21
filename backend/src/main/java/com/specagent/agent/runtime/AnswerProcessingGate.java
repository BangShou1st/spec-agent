package com.specagent.agent.runtime;

import com.specagent.answer.Answer;
import com.specagent.answer.AnswerService;
import com.specagent.patch.AnswerPatchService;
import com.specagent.route.RouteHistoryResolver;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * One shared judge for "does the state a spec would be derived from still owe
 * a processed answer?" — used identically by the command surface (before a
 * GENERATE_ARTIFACT run is queued) and by the executor (before an already
 * queued run is claimed and executed).
 *
 * <p>The judge reads the route's <em>effective</em> answer history — the route's
 * own answers plus the frozen inherited prefix ({@code route_inherited_answers})
 * — exactly the set {@code ContextBuilder} folds into the spec context. The
 * previous two gates each looked only at the route's own tip answer, so forking
 * from an answered node whose STATE_UPDATE never completed let the branch
 * publish a spec that silently omitted the user's inherited answer.
 *
 * <p>Answers with an existing {@code AnswerPatch} checkpoint pass: a failed or
 * stale DECISION after the STATE_UPDATE checkpoint does not reopen the gate
 * (repair reuses the checkpoint; the claims are already in the state).
 *
 * <p>Sibling isolation: only answers on this route's own tip lineage are
 * judged, so an unrelated sibling route's unprocessed answer never blocks this
 * route's generation.
 */
@Service
public class AnswerProcessingGate {

    private final AnswerService answerService;
    private final AnswerPatchService answerPatchService;
    private final RouteHistoryResolver routeHistoryResolver;

    public AnswerProcessingGate(AnswerService answerService,
                                AnswerPatchService answerPatchService,
                                RouteHistoryResolver routeHistoryResolver) {
        this.answerService = answerService;
        this.answerPatchService = answerPatchService;
        this.routeHistoryResolver = routeHistoryResolver;
    }

    /**
     * The first effective answer of the route whose processing never landed a
     * checkpoint, in root-to-tip order — empty when every effective answer has
     * its {@code AnswerPatch}.
     *
     * @param routeId the route the spec would be generated from
     * @param tipNodeId the route's current tip; the spec context replays its
     *        parent lineage
     */
    public Optional<Answer> firstUnprocessedAnswer(UUID routeId, UUID tipNodeId) {
        if (routeId == null || tipNodeId == null) {
            return Optional.empty();
        }
        List<UUID> lineage = routeHistoryResolver.resolveLineage(tipNodeId);
        for (Answer answer : routeHistoryResolver.resolveEffectiveAnswers(routeId, lineage)) {
            if (answerPatchService.findBySourceAnswerId(answer.id()).isEmpty()) {
                return Optional.of(answer);
            }
        }
        return Optional.empty();
    }
}
