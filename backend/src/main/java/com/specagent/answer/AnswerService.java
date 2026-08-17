package com.specagent.answer;

import com.specagent.common.Ids;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Records immutable answers and enforces single finalization per route flow.
 *
 * <p>An answer is immutable. Within the current route flow, a node may be
 * finalized exactly once; re-answering must create a new route, replacement
 * node, or answer revision, never overwrite the existing answer.
 */
@Service
public class AnswerService {

    private final AnswerRepository answerRepository;

    public AnswerService(AnswerRepository answerRepository) {
        this.answerRepository = answerRepository;
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
        UUID answerId = Ids.random();
        Instant now = Instant.now();
        Answer answer = new Answer(answerId, projectId, routeId, nodeId,
                selectedOptionId, freeText, createdByUser, now);
        answerRepository.save(answer);
        return answer;
    }

    public Optional<Answer> getAnswer(UUID answerId) {
        return answerRepository.findById(answerId);
    }
}
