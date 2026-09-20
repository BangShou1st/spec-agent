package com.specagent.agent.runevent;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RunProgressRecorderTest {

    private final AgentRunEventService eventService = mock(AgentRunEventService.class);
    private final RunProgressRecorder recorder = new RunProgressRecorder(eventService);

    @Test
    void noteComposesWhitelistedPayload() {
        UUID runId = UUID.randomUUID();

        recorder.note(runId, AgentRunPhase.STATE_UPDATED, "需求要点整理完成");

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventService).append(eq(runId), eq(AgentRunPhase.STATE_UPDATED),
                eq(RunProgressRecorder.PROCESS_NOTE_EVENT), payload.capture());
        assertThat(payload.getValue())
                .containsEntry("summary", "需求要点整理完成")
                .doesNotContainKey("items");
    }

    @Test
    void longSummaryAndItemsAreTruncatedAndCapped() {
        UUID runId = UUID.randomUUID();
        String longSummary = "长".repeat(RunProgressRecorder.MAX_SUMMARY_LENGTH + 50);
        List<String> items = java.util.stream.IntStream.rangeClosed(1, RunProgressRecorder.MAX_ITEMS + 2)
                .mapToObj(i -> "要点" + i + "：" + "述".repeat(RunProgressRecorder.MAX_ITEM_LENGTH))
                .toList();

        recorder.noteWithItems(runId, AgentRunPhase.STATE_UPDATED, longSummary, items);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventService).append(eq(runId), any(AgentRunPhase.class), any(), payload.capture());
        assertThat((String) payload.getValue().get("summary"))
                .hasSize(RunProgressRecorder.MAX_SUMMARY_LENGTH);
        assertThat((List<String>) payload.getValue().get("items"))
                .hasSize(RunProgressRecorder.MAX_ITEMS)
                .allSatisfy(item -> assertThat(item).hasSize(RunProgressRecorder.MAX_ITEM_LENGTH));
    }

    @Test
    void emptyItemsAreOmitted() {
        UUID runId = UUID.randomUUID();

        recorder.noteWithItems(runId, AgentRunPhase.DECIDING, "已确定下一步动作", List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventService).append(eq(runId), any(AgentRunPhase.class), any(), payload.capture());
        assertThat(payload.getValue()).doesNotContainKey("items");
    }
}
