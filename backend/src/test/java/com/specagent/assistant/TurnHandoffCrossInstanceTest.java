package com.specagent.assistant;

import com.specagent.assistant.conversation.GlobalAssistantConversationService;
import com.specagent.assistant.conversation.GlobalAssistantRun;
import com.specagent.assistant.conversation.GlobalAssistantRunRepository;
import com.specagent.assistant.conversation.GlobalAssistantRunStatus;
import com.specagent.assistant.conversation.GlobalAssistantThread;
import com.specagent.assistant.conversation.PendingTurn;
import com.specagent.assistant.conversation.PendingTurnRepository;
import com.specagent.assistant.runtime.TurnHandoffCatchUpPoller;
import com.specagent.assistant.runtime.TurnHandoffService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:TurnHandoffCrossInstanceTest.java
 *
 * 测试目标:跨实例的轮次续转(design §6.5、§10)。
 *
 * <p>终态续转原本由进程内 {@code RunTerminalEvent} + AFTER_COMMIT 监听器驱动:
 * 若 run 在 Worker A 上终态化,而 steer 的未决轮次属于实例 B,监听器永远不会触发,
 * 该轮次会一直搁置到下一次启动的 {@code recoverStranded}。
 *
 * <p>本测试模拟该形态:直接写入一个未决轮次(不经过本进程的 acceptSteer,因此没有
 * 任何进程内事件),Run 终态化后由轮询器基于数据库事实完成续转。验证:
 *
 * <ol>
 *   <li>轮询器为搁置的未决轮次创建继任 Run;</li>
 *   <li>未决轮次被标记消费(恰好一次);</li>
 *   <li>重复运行轮询器<b>不会</b>创建第二个继任 Run(幂等);</li>
 *   <li>有活跃 run 的会话不被续转。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class TurnHandoffCrossInstanceTest {

    /**
     * 直接构造轮询器而不是开启 {@code worker.enabled}:该开关会同时启用
     * {@code @EnableScheduling},让后台轮询器与测试并发创建 run/事件,导致清理
     * 出现难以复现的外键竞态。这里显式调用 {@code catchUp()} 验证同一段逻辑,
     * 测试因此完全确定。
     */
    private TurnHandoffCatchUpPoller poller;

    @Autowired TurnHandoffService handoff;
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired GlobalAssistantRunRepository runs;
    @Autowired PendingTurnRepository pending;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired com.specagent.assistant.runtime.RunDispatcher dispatcher;

    @org.junit.jupiter.api.BeforeEach
    void wirePoller() {
        // 任务级所有权模式:续转的继任 Run 由公共调度层认领,续转本身不直接派发
        // 到本地执行队列。这既是多 Worker 的真实形态,也让本测试完全确定——
        // 否则本地派发会启动一次异步 GA 执行(本地无模型配置,必然失败),
        // 与清理产生竞态。
        poller = new TurnHandoffCatchUpPoller(handoff, pending, runs, dispatcher,
                new com.specagent.runtime.execution.ExecutionOwnershipMode(true));
    }

    private final List<UUID> touchedThreads = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID threadId : touchedThreads) {
            // 外键顺序:pending_turns 与 run_events 都引用 runs,必须先删。
            jdbc.update("DELETE FROM global_assistant_pending_turns WHERE thread_id = :t",
                    Map.of("t", threadId));
            jdbc.update("DELETE FROM global_assistant_run_events WHERE run_id IN "
                    + "(SELECT id FROM global_assistant_runs WHERE thread_id = :t)",
                    Map.of("t", threadId));
            jdbc.update("DELETE FROM global_assistant_messages WHERE thread_id = :t",
                    Map.of("t", threadId));
            jdbc.update("DELETE FROM global_assistant_runs WHERE thread_id = :t", Map.of("t", threadId));
            jdbc.update("DELETE FROM global_assistant_threads WHERE id = :t", Map.of("t", threadId));
        }
        touchedThreads.clear();
    }

    private GlobalAssistantThread newThread() {
        GlobalAssistantThread thread = conversations.createThread();
        touchedThreads.add(thread.id());
        return thread;
    }

    /** 模拟"另一个实例受理了 steer 并留下了未决轮次,然后那个实例死了"。 */
    private PendingTurn strandedTurn(UUID threadId, UUID interruptedRunId) {
        PendingTurn turn = pending.insert(threadId, interruptedRunId, "请改用另一种方式说明",
                "{\"currentPage\":\"PROJECT\"}");
        return turn;
    }

    @Test
    void strandedPendingTurnIsHandedOffByThePollerWithoutAnyInProcessEvent() {
        GlobalAssistantThread thread = newThread();
        // 一个已终态的中断 run:它的终态事件是在"另一个实例"上提交的,
        // 本进程从未收到任何 RunTerminalEvent。
        GlobalAssistantRun interrupted = conversations.createRun(thread.id(),
                "langchain-ga.v1", "ga-host.v1", "v1");
        runs.markRunning(interrupted.id());
        runs.terminalize(interrupted.id(), GlobalAssistantRunStatus.FAILED, "RUN_INTERRUPTED");

        PendingTurn turn = strandedTurn(thread.id(), interrupted.id());
        assertThat(turn.status().name())
                .as("a freshly inserted pending turn is unresolved (PENDING)")
                .isIn("PENDING", "CLAIMED");

        poller.catchUp();

        // 继任 Run 被创建,未决轮次被消费。
        var unresolved = pending.findUnresolvedByThread(thread.id());
        assertThat(unresolved)
                .as("the poller must consume the stranded pending turn")
                .isEmpty();
        var active = runs.findActiveByThread(thread.id());
        assertThat(active).as("a successor run must have been created").isPresent();
        assertThat(active.get().id()).isNotEqualTo(interrupted.id());
    }

    @Test
    void repeatedCatchUpNeverCreatesASecondSuccessor() {
        GlobalAssistantThread thread = newThread();
        GlobalAssistantRun interrupted = conversations.createRun(thread.id(),
                "langchain-ga.v1", "ga-host.v1", "v1");
        runs.markRunning(interrupted.id());
        runs.terminalize(interrupted.id(), GlobalAssistantRunStatus.FAILED, "RUN_INTERRUPTED");
        strandedTurn(thread.id(), interrupted.id());

        poller.catchUp();
        UUID firstSuccessor = runs.findActiveByThread(thread.id()).orElseThrow().id();
        // 让继任也终态化,然后重复轮询:绝不能出现第三个 run。
        runs.terminalize(firstSuccessor, GlobalAssistantRunStatus.COMPLETED, null);

        poller.catchUp();
        poller.catchUp();

        Integer totalRuns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM global_assistant_runs WHERE thread_id = :t",
                Map.of("t", thread.id()), Integer.class);
        assertThat(totalRuns)
                .as("handoff must be exactly-once in effect even when attempted repeatedly")
                .isEqualTo(2);
    }

    @Test
    void pendingTurnOfAThreadWithAnActiveRunIsNotHandedOff() {
        GlobalAssistantThread thread = newThread();
        GlobalAssistantRun interrupted = conversations.createRun(thread.id(),
                "langchain-ga.v1", "ga-host.v1", "v1");
        runs.markRunning(interrupted.id());
        runs.terminalize(interrupted.id(), GlobalAssistantRunStatus.FAILED, "RUN_INTERRUPTED");
        strandedTurn(thread.id(), interrupted.id());

        // 该会话现在有一个活跃 run(例如用户又发了一条消息):绝不续转。
        GlobalAssistantRun activeRun = conversations.createRun(thread.id(),
                "langchain-ga.v1", "ga-host.v1", "v1");

        poller.catchUp();

        assertThat(pending.findUnresolvedByThread(thread.id()))
                .as("a thread with active work must not be handed off")
                .isPresent();
        assertThat(runs.findActiveByThread(thread.id()).orElseThrow().id())
                .isEqualTo(activeRun.id());
    }
}
