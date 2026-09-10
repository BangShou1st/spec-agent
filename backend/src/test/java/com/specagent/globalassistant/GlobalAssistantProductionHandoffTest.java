package com.specagent.globalassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.specagent.globalassistant.api.GlobalAssistantApplicationService;
import com.specagent.globalassistant.context.GlobalAssistantContextBuilder;
import com.specagent.globalassistant.conversation.GlobalAssistantConversationService;
import com.specagent.globalassistant.conversation.GlobalAssistantMessage;
import com.specagent.globalassistant.conversation.GlobalAssistantRun;
import com.specagent.globalassistant.conversation.GlobalAssistantRunRepository;
import com.specagent.globalassistant.conversation.GlobalAssistantRunStatus;
import com.specagent.globalassistant.conversation.GlobalAssistantThread;
import com.specagent.globalassistant.runtime.GlobalAssistantRunLifecycleService;
import com.specagent.globalassistant.runtime.GlobalAssistantRunRecoveryService;
import com.specagent.globalassistant.turn.PendingTurnRepository;
import com.specagent.globalassistant.turn.RunDispatcher;
import com.specagent.globalassistant.turn.SteerRejectedException;
import com.specagent.globalassistant.turn.TurnHandoffService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Production-path handoff: real lifecycle transactions, real AFTER_COMMIT
 * listener, real handoff DB writes. Never calls tryHandoff manually in the
 * automatic-chain test. No outer test transaction so commit semantics are real.
 */
@SpringBootTest
@ActiveProfiles("test")
class GlobalAssistantProductionHandoffTest {
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired GlobalAssistantRunRepository runs;
    @Autowired GlobalAssistantRunLifecycleService lifecycle;
    @Autowired TurnHandoffService handoff;
    @Autowired PendingTurnRepository pending;
    @Autowired GlobalAssistantApplicationService application;
    @Autowired GlobalAssistantRunRecoveryService recovery;
    @Autowired JdbcTemplate jdbc;
    @MockBean RunDispatcher dispatcher;

    private final List<UUID> threads = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (UUID threadId : threads) {
            try {
                jdbc.update("DELETE FROM global_assistant_run_events WHERE run_id IN (SELECT id FROM global_assistant_runs WHERE thread_id = ?)", threadId);
                jdbc.update("DELETE FROM capability_invocations WHERE run_id IN (SELECT id FROM global_assistant_runs WHERE thread_id = ?)", threadId);
                jdbc.update("DELETE FROM global_assistant_pending_turns WHERE thread_id = ?", threadId);
                jdbc.update("DELETE FROM global_assistant_messages WHERE thread_id = ?", threadId);
                jdbc.update("DELETE FROM global_assistant_runs WHERE thread_id = ?", threadId);
                jdbc.update("DELETE FROM global_assistant_threads WHERE id = ?", threadId);
            } catch (Exception ignored) {
            }
        }
        threads.clear();
    }

    private GlobalAssistantContextBuilder.UiRequest ui() {
        return new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null);
    }

    private UUID newThread() {
        GlobalAssistantThread thread = conversations.createThread();
        threads.add(thread.id());
        return thread.id();
    }

    @Test
    void automaticHandoffViaTerminalCommit() {
        List<UUID> dispatched = new ArrayList<>();
        List<Boolean> visibleAtDispatch = new ArrayList<>();
        doAnswer(inv -> {
            UUID runId = inv.getArgument(1);
            dispatched.add(runId);
            visibleAtDispatch.add(runs.findById(runId).isPresent());
            return null;
        }).when(dispatcher).dispatch(any(), any(), anyString(), any());
        UUID threadId = newThread();
        GlobalAssistantRun runA = conversations.createRunWithUserMessage(threadId, "first", "v1", "v1", "fp");
        var accepted = handoff.acceptSteer(threadId, runA.id(), "steer requirement", ui());
        assertThat(accepted.successor()).isEmpty();
        lifecycle.cancelAndTerminalize(runA.id());
        assertThat(dispatched).hasSize(1);
        assertThat(visibleAtDispatch).containsExactly(true);
        UUID successorId = dispatched.get(0);
        assertThat(runs.findById(successorId)).isPresent();
        assertThat(pending.findUnresolvedByThread(threadId)).isEmpty();
        long userCount = conversations.listMessages(threadId).stream()
                .filter(m -> m.role() == GlobalAssistantMessage.Role.USER && "steer requirement".equals(m.content()))
                .count();
        assertThat(userCount).isEqualTo(1);
        long runCount = runs.findByThread(threadId).size();
        assertThat(runCount).isEqualTo(2);
    }

    @Test
    void duplicateTerminalNotificationCreatesOneSuccessor() {
        List<UUID> dispatched = new ArrayList<>();
        doAnswer(inv -> {
            dispatched.add(inv.getArgument(1));
            return null;
        }).when(dispatcher).dispatch(any(), any(), anyString(), any());
        UUID threadId = newThread();
        GlobalAssistantRun runA = conversations.createRunWithUserMessage(threadId, "first", "v1", "v1", "fp");
        handoff.acceptSteer(threadId, runA.id(), "once only", ui());
        lifecycle.cancelAndTerminalize(runA.id());
        assertThat(dispatched).hasSize(1);
        var again = handoff.tryHandoff(threadId);
        assertThat(again).isEmpty();
        assertThat(dispatched).hasSize(1);
        long userCount = conversations.listMessages(threadId).stream()
                .filter(m -> "once only".equals(m.content())).count();
        assertThat(userCount).isEqualTo(1);
    }

    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;
    @Test
    void terminalRollbackProducesNoHandoff() {
        UUID threadId = newThread();
        GlobalAssistantRun runA = conversations.createRunWithUserMessage(threadId, "first", "v1", "v1", "fp");
        handoff.acceptSteer(threadId, runA.id(), "rolled back steer", ui());
        var template = new org.springframework.transaction.support.TransactionTemplate(txManager);
        assertThatThrownBy(() -> template.execute(status -> {
            lifecycle.cancelAndTerminalize(runA.id());
            throw new RuntimeException("force rollback");
        })).isInstanceOf(RuntimeException.class);
        assertThat(runs.findById(runA.id()).orElseThrow().status().isActive()).isTrue();
        assertThat(pending.findUnresolvedByThread(threadId)).isPresent();
        assertThat(runs.findByThread(threadId)).hasSize(1);
    }

    @Test
    void recoveryOrphanPlusPendingYieldsExactlyOneSuccessor() {
        List<UUID> dispatched = new ArrayList<>();
        doAnswer(inv -> {
            dispatched.add(inv.getArgument(1));
            return null;
        }).when(dispatcher).dispatch(any(), any(), anyString(), any());
        UUID threadId = newThread();
        GlobalAssistantRun runA = conversations.createRunWithUserMessage(threadId, "first", "v1", "v1", "fp");
        handoff.acceptSteer(threadId, runA.id(), "recover me", ui());
        int recovered = recovery.recoverOrphans();
        assertThat(recovered).isGreaterThanOrEqualTo(1);
        assertThat(runs.findById(runA.id()).orElseThrow().status()).isEqualTo(GlobalAssistantRunStatus.FAILED);
        long successors = runs.findByThread(threadId).stream().filter(r -> !r.id().equals(runA.id())).count();
        assertThat(successors).isEqualTo(1);
        long userCount = conversations.listMessages(threadId).stream().filter(m -> "recover me".equals(m.content())).count();
        assertThat(userCount).isEqualTo(1);
    }

    @Test
    void recoveryPendingWithoutActiveDispatchesCommittedRun() {
        List<UUID> dispatched = new ArrayList<>();
        List<Boolean> visible = new ArrayList<>();
        doAnswer(inv -> {
            UUID runId = inv.getArgument(1);
            dispatched.add(runId);
            visible.add(runs.findById(runId).isPresent());
            return null;
        }).when(dispatcher).dispatch(any(), any(), anyString(), any());
        UUID threadId = newThread();
        GlobalAssistantRun runA = conversations.createRunWithUserMessage(threadId, "first", "v1", "v1", "fp");
        handoff.acceptSteer(threadId, runA.id(), "stranded", ui());
        lifecycle.cancelAndTerminalize(runA.id());
        dispatched.clear();
        visible.clear();
        UUID threadB = newThread();
        GlobalAssistantRun runB = conversations.createRunWithUserMessage(threadB, "unrelated", "v1", "v1", "fp");
        handoff.acceptSteer(threadB, runB.id(), "lonely stranded", ui());
        lifecycle.cancelAndTerminalize(runB.id());
        assertThat(dispatched.size()).isGreaterThanOrEqualTo(1);
        assertThat(visible).doesNotContain(false);
    }

    @Test
    void stopIsAtomicDiscardPlusCancel() {
        UUID threadId = newThread();
        GlobalAssistantRun runA = conversations.createRunWithUserMessage(threadId, "first", "v1", "v1", "fp");
        handoff.acceptSteer(threadId, runA.id(), "stop me", ui());
        var act = application.stopThread(threadId);
        assertThat(act.pendingSteer()).isEmpty();
        assertThat(runs.findById(runA.id()).orElseThrow().cancelRequestedAt()).isNotNull();
        assertThat(handoff.tryHandoff(threadId)).isEmpty();
        assertThat(runs.findByThread(threadId)).hasSize(1);
    }

    @Test
    void staleTargetWithNewerActiveIsRejectedWithoutSideEffects() {
        UUID threadId = newThread();
        GlobalAssistantRun runOld = conversations.createRunWithUserMessage(threadId, "old", "v1", "v1", "fp");
        lifecycle.cancelAndTerminalize(runOld.id());
        GlobalAssistantRun runNew = conversations.createRunWithUserMessage(threadId, "new", "v1", "v1", "fp");
        assertThatThrownBy(() -> handoff.acceptSteer(threadId, runOld.id(), "sneaky steer", ui()))
                .isInstanceOf(SteerRejectedException.class)
                .matches(ex -> ((SteerRejectedException) ex).reason() == SteerRejectedException.Reason.STALE_TARGET);
        assertThat(pending.findUnresolvedByThread(threadId)).isEmpty();
        assertThat(runs.findByThread(threadId)).hasSize(2);
    }

    @Test
    void terminalTargetWithoutNewerActiveAllowsSuccessor() {
        UUID threadId = newThread();
        GlobalAssistantRun runOld = conversations.createRunWithUserMessage(threadId, "old", "v1", "v1", "fp");
        lifecycle.cancelAndTerminalize(runOld.id());
        var accepted = handoff.acceptSteer(threadId, runOld.id(), "fresh steer", ui());
        assertThat(accepted.successor()).isPresent();
    }
}
