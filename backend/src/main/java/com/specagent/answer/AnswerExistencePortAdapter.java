package com.specagent.answer;

import com.specagent.common.AnswerExistencePort;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Thin implementation of the graph-consumed {@link AnswerExistencePort} on top
 * of {@link AnswerRepository}.
 *
 * <p>It adds no logic of its own: existence is the existing
 * {@code existsByNodeId} statement and nothing else. Its only job is to keep
 * the port's dependency direction intact, so the graph package never imports
 * the answer package.
 */
@Component
public class AnswerExistencePortAdapter implements AnswerExistencePort {

    private final AnswerRepository answerRepository;

    public AnswerExistencePortAdapter(AnswerRepository answerRepository) {
        this.answerRepository = answerRepository;
    }

    @Override
    public boolean nodeHasFinalizedAnswer(UUID nodeId) {
        return answerRepository.existsByNodeId(nodeId);
    }
}
