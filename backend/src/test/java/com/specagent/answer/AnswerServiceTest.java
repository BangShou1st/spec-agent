package com.specagent.answer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the read boundary of {@link AnswerService}.
 *
 * <p>Covers the route-scoped batch answer read used by the graph read model.
 * The read is a pure delegation to the repository query: no lifecycle logic,
 * copying, mutation, or fallback belongs in the service layer.
 */
@ExtendWith(MockitoExtension.class)
class AnswerServiceTest {

    @Mock
    AnswerRepository answerRepository;
    @InjectMocks
    AnswerService service;

    @Test
    void findAnswersForRouteAndNodeIdsDelegatesReadOnlyQuery() {
        UUID projectId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        List<UUID> nodeIds = List.of(nodeId);
        Answer answer = new Answer(
                UUID.randomUUID(), projectId, routeId, nodeId,
                UUID.randomUUID().toString(), "free text", "user",
                Instant.parse("2026-08-18T00:00:00Z"));
        when(answerRepository.findByRouteAndNodeIds(routeId, nodeIds)).thenReturn(List.of(answer));

        assertThat(service.findAnswersForRouteAndNodeIds(routeId, nodeIds)).containsExactly(answer);
        verify(answerRepository).findByRouteAndNodeIds(routeId, nodeIds);
        verifyNoMoreInteractions(answerRepository);
    }
}
