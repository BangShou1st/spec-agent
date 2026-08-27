package com.specagent.answer;

import com.specagent.common.Ids;
import com.specagent.graph.GraphInvariantValidator;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Records immutable answers and enforces single finalization per route flow.
 *
 * <p>An answer is immutable. Within the current route flow, a node may be
 * finalized exactly once; re-answering must create a new route, replacement
 * node, or answer revision, never overwrite the existing answer.
 *
 * <p>Shared-state invariant: a canonical Question Node carries exactly one
 * immutable semantic Answer identity project-wide. Once any route has a
 * finalized Answer for the node, no other route may finalize a second Answer
 * on the same canonical node — branches reference the same Answer through
 * inherited refs, and re-answering creates a new Question Node (see
 * {@link GraphInvariantValidator#validateSharedQuestionState}).
 */
@Service
public class AnswerService {

    private final AnswerRepository answerRepository;
    private final GraphInvariantValidator invariantValidator;

    public AnswerService(AnswerRepository answerRepository,
                         GraphInvariantValidator invariantValidator) {
        this.answerRepository = answerRepository;
        this.invariantValidator = invariantValidator;
    }

    public Answer finalizeAnswer(UUID projectId,
                                 UUID routeId,
                                 UUID nodeId,
                                 String selectedOptionId,
                                 String freeText,
                                 String createdByUser) {
        if (answerRepository.existsByRouteAndNode(routeId, nodeId)) {
            throw new IllegalStateException(
                    "Answer already finalized for node " + nodeId + " in route " + routeId);
        }
        invariantValidator.validateSharedQuestionState(projectId, nodeId);
        UUID answerId = Ids.random();
        Instant now = Instant.now();
        Answer answer = new Answer(answerId, projectId, routeId, nodeId,
                selectedOptionId, freeText, createdByUser, now);
        answerRepository.save(answer);
        return answer;
    }

    /**
     * Read-only batch read of the answers finalized for one route on the given
     * node ids. Pure delegation to the repository query; no lifecycle logic,
     * copying, mutation, or fallback belongs here.
     */
    public List<Answer> findAnswersForRouteAndNodeIds(UUID routeId, List<UUID> nodeIds) {
        return answerRepository.findByRouteAndNodeIds(routeId, nodeIds);
    }

    public Optional<Answer> getAnswer(UUID answerId) {
        return answerRepository.findById(answerId);
    }

    /**
     * Returns the single finalized answer for a given route and node, or empty.
     */
    public Optional<Answer> findAnswerForNode(UUID routeId, UUID nodeId) {
        return answerRepository.findByRouteAndNodeIds(routeId, List.of(nodeId))
                .stream().findFirst();
    }

    /**
     * Read-only existence check for the single-finalization invariant:
     * whether the given route flow already has a finalized answer for the node.
     */
    public boolean existsAnswerFor(UUID routeId, UUID nodeId) {
        return answerRepository.existsByRouteAndNode(routeId, nodeId);
    }
}
