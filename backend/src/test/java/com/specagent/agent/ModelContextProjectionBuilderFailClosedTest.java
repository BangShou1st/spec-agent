package com.specagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.answer.Answer;
import com.specagent.answer.AnswerRepository;
import com.specagent.common.Json;
import com.specagent.context.ContextOperationType;
import com.specagent.context.ContextSnapshot;
import com.specagent.context.RequirementStateBuilder;
import com.specagent.node.Node;
import com.specagent.node.NodeRepository;
import com.specagent.patch.AnswerPatch;
import com.specagent.patch.AnswerPatchRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The projection is a trust boundary: if the frozen snapshot's inclusion
 * manifest points at a runtime record that no longer exists, building the
 * model input must fail closed instead of silently projecting partial context.
 */
class ModelContextProjectionBuilderFailClosedTest {

    private final NodeRepository nodeRepository = mock(NodeRepository.class);
    private final AnswerRepository answerRepository = mock(AnswerRepository.class);
    private final AnswerPatchRepository answerPatchRepository = mock(AnswerPatchRepository.class);
    private final RequirementStateBuilder requirementStateBuilder = mock(RequirementStateBuilder.class);

    private final ModelContextProjectionBuilder builder = new ModelContextProjectionBuilder(
            nodeRepository, answerRepository, answerPatchRepository, requirementStateBuilder,
            new Json(new ObjectMapper()));

    private ContextSnapshot snapshot(UUID nodeId, List<UUID> answerIds, List<UUID> patchIds) {
        return new ContextSnapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                nodeId, ContextOperationType.NORMAL,
                List.of(nodeId), answerIds, patchIds, List.of(), "{}", "hash", Instant.now());
    }

    @Test
    void projectionFailsWhenIncludedNodeMissing() {
        UUID nodeId = UUID.randomUUID();
        when(nodeRepository.findById(nodeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> builder.buildContext(snapshot(nodeId, List.of(), List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("included missing node")
                .hasMessageContaining(nodeId.toString());
    }

    @Test
    void projectionFailsWhenIncludedAnswerMissing() {
        UUID nodeId = UUID.randomUUID();
        UUID answerId = UUID.randomUUID();
        when(nodeRepository.findById(nodeId)).thenReturn(Optional.of(mock(Node.class)));
        when(answerRepository.findById(answerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> builder.buildContext(snapshot(nodeId, List.of(answerId), List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("included missing answer")
                .hasMessageContaining(answerId.toString());
    }

    @Test
    void projectionFailsWhenIncludedPatchMissing() {
        UUID nodeId = UUID.randomUUID();
        UUID answerId = UUID.randomUUID();
        UUID patchId = UUID.randomUUID();
        when(nodeRepository.findById(nodeId)).thenReturn(Optional.of(mock(Node.class)));
        when(answerRepository.findById(answerId)).thenReturn(Optional.of(mock(Answer.class)));
        when(answerPatchRepository.findById(patchId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> builder.buildContext(snapshot(nodeId, List.of(answerId), List.of(patchId))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("included missing patch")
                .hasMessageContaining(patchId.toString());
    }
}