package com.specagent.agent.runevent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 文件名:RunProgressAssemblerTest.java
 *
 * 测试目标:验证 RunProgressAssembler 把运行事件装配为进度视图——空事件产生空视图、
 * 只装配白名单事件且非 note 事件不暴露任何 payload 内容、取最新的 summary、
 * 步骤数量截断到最新的 MAX_STEPS 窗口。
 */
class RunProgressAssemblerTest {

    private final AgentRunEventService eventService = mock(AgentRunEventService.class);
    private final RunProgressAssembler assembler = new RunProgressAssembler(eventService);

    @Test
    void emptyEventsYieldEmptyView() {
        when(eventService.findByRunId(UUID.randomUUID())).thenReturn(List.of());
        RunProgressView view = assembler.assemble(UUID.randomUUID());
        assertThat(view.phase()).isNull();
        assertThat(view.summary()).isNull();
        assertThat(view.steps()).isEmpty();
    }

    @Test
    void assemblesWhitelistedStepsAndLatestSummary() {
        UUID runId = UUID.randomUUID();
        when(eventService.findByRunId(runId)).thenReturn(List.of(
                event(runId, 1, AgentRunPhase.SNAPSHOT_BUILT, "SNAPSHOT_BUILT", Map.of("snapshotId", "s1")),
                event(runId, 2, AgentRunPhase.STATE_UPDATED, RunProgressRecorder.PROCESS_NOTE_EVENT,
                        Map.of("summary", "需求要点整理完成，共 2 条",
                                "items", List.of("要点一", "要点二")))));

        RunProgressView view = assembler.assemble(runId);

        assertThat(view.phase()).isEqualTo(AgentRunPhase.STATE_UPDATED.code());
        assertThat(view.summary()).isEqualTo("需求要点整理完成，共 2 条");
        assertThat(view.steps()).hasSize(2);
        RunProgressView.Step note = view.steps().get(1);
        assertThat(note.sequence()).isEqualTo(2);
        assertThat(note.phase()).isEqualTo(AgentRunPhase.STATE_UPDATED.code());
        assertThat(note.event()).isEqualTo(RunProgressRecorder.PROCESS_NOTE_EVENT);
        assertThat(note.summary()).isEqualTo("需求要点整理完成，共 2 条");
        assertThat(note.items()).containsExactly("要点一", "要点二");
        // 非 note 事件不暴露任何 payload 内容。
        assertThat(view.steps().get(0).summary()).isNull();
        assertThat(view.steps().get(0).items()).isNull();
    }

    @Test
    void capsStepsToTheLatestWindow() {
        UUID runId = UUID.randomUUID();
        List<AgentRunEvent> events = IntStream.rangeClosed(1, RunProgressAssembler.MAX_STEPS + 10)
                .mapToObj(i -> event(runId, i, AgentRunPhase.DECIDING, "E" + i, Map.of()))
                .toList();
        when(eventService.findByRunId(runId)).thenReturn(events);

        RunProgressView view = assembler.assemble(runId);

        assertThat(view.steps()).hasSize(RunProgressAssembler.MAX_STEPS);
        assertThat(view.steps().get(0).sequence())
                .isEqualTo(events.size() - RunProgressAssembler.MAX_STEPS + 1);
        assertThat(view.steps().get(RunProgressAssembler.MAX_STEPS - 1).sequence())
                .isEqualTo(events.size());
    }

    private static AgentRunEvent event(UUID runId, int sequence, AgentRunPhase phase,
                                       String eventType, Map<String, Object> payload) {
        return new AgentRunEvent(UUID.randomUUID(), runId, sequence, phase, eventType,
                payload, Instant.now());
    }
}
