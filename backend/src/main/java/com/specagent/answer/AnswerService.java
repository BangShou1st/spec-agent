package com.specagent.answer;

import com.specagent.common.Ids;
import com.specagent.common.SharedQuestionStatePort;
import com.specagent.node.Node;
import com.specagent.node.NodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * {@link SharedQuestionStatePort#validateSharedQuestionState}).
 */
@Service
public class AnswerService {

    private final AnswerRepository answerRepository;
    private final NodeRepository nodeRepository;
    private final SharedQuestionStatePort sharedQuestionStatePort;
    private final ProjectRowLockPort projectRowLock;
    private final AnswerIndexPort answerIndexPort;

    public AnswerService(AnswerRepository answerRepository,
                         NodeRepository nodeRepository,
                         SharedQuestionStatePort sharedQuestionStatePort,
                         ProjectRowLockPort projectRowLock,
                         AnswerIndexPort answerIndexPort) {
        this.answerRepository = answerRepository;
        this.nodeRepository = nodeRepository;
        this.sharedQuestionStatePort = sharedQuestionStatePort;
        this.projectRowLock = projectRowLock;
        this.answerIndexPort = answerIndexPort;
    }

    /**
     * Finalizes the immutable answer for one route flow on a node.
     *
     * <p>The whole finalization is one transaction. Concurrent routes sharing
     * the same canonical Question must never both persist an Answer: the
     * canonical node row is locked ({@code SELECT ... FOR UPDATE}) before the
     * node-wide existence re-check, so exactly one concurrent transaction wins
     * and every later one observes the persisted Answer through the
     * {@link SharedQuestionStatePort#validateSharedQuestionState} conflict
     * path instead of inserting a second Answer identity.
     */
    @Transactional
    public Answer finalizeAnswer(UUID projectId,
                                 UUID routeId,
                                 UUID nodeId,
                                 String selectedOptionId,
                                 String freeText,
                                 String createdByUser) {
        return finalizeAnswerWithSelections(projectId, routeId, nodeId,
                selectedOptionId == null ? List.<String>of() : List.of(selectedOptionId),
                freeText, createdByUser);
    }

    /** Multi-select variant: {@code selectedOptionIds} is the FULL selection in user order. */
    @Transactional
    public Answer finalizeAnswerWithSelections(UUID projectId,
                                               UUID routeId,
                                               UUID nodeId,
                                               List<String> selectedOptionIds,
                                               String freeText,
                                               String createdByUser) {
        if (answerRepository.existsByRouteAndNode(routeId, nodeId)) {
            throw new IllegalStateException(
                    "Answer already finalized for node " + nodeId + " in route " + routeId);
        }
        // Serialize finalization with Undo/Redo and other project-level
        // mutations: the project row is locked first (matching the Undo path's
        // project -> node order) so the answer INSERT's foreign-key key-share
        // on the project row can never deadlock against an Undo that holds the
        // project lock while waiting for this node.
        projectRowLock.lockProject(projectId);
        // Serialize concurrent finalization of the same canonical node: after
        // this lock the node-wide existence check below is authoritative.
        nodeRepository.lockById(nodeId);
        // A node retracted by a winning Undo/Redo must never gain an immutable
        // Answer. Without this check an Undo that committed its retraction
        // first would lose the race to a later finalization, leaving a
        // retracted node carrying an immutable Answer (the exact invariant
        // Undo/Redo must uphold). The re-read happens under the node lock, so
        // it observes the authoritative retraction state.
        Node lockedNode = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + nodeId));
        if (lockedNode.isRetracted()) {
            throw new IllegalStateException(
                    "RETRACTED_NODE_REFERENCE: cannot finalize an immutable Answer on a retracted node " + nodeId);
        }
        sharedQuestionStatePort.validateSharedQuestionState(projectId, nodeId);
        UUID answerId = Ids.random();
        Instant now = Instant.now();
        // The legacy column keeps the FIRST selected option so every existing
        // single-selection consumer reads the same value it always has.
        String firstSelectedOptionId = selectedOptionIds == null || selectedOptionIds.isEmpty()
                ? null : selectedOptionIds.get(0);
        Answer answer = new Answer(answerId, projectId, routeId, nodeId,
                firstSelectedOptionId, selectedOptionIds, freeText, createdByUser, now);
        answerRepository.save(answer);
        answerIndexPort.index(answer);
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
