package com.specagent.agent;

import com.specagent.agent.runtime.AgentRunStatus;

import com.specagent.agent.DraftQuestionAsyncPollerIntegrationTest;
import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunService;

import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:DraftQuestionAsyncPollerIntegrationTest.java
 *
 * 测试目标:复现 BUG-03:E2E 套件对 DRAFT_QUESTION 走的是真实异步 worker 轮询器
 * ({@code spec.agent.brain.worker.enabled=true}),而所有既有后端测试都通过
 * {@link com.specagent.agent.DecisionCycleTestDriver} 同步驱动 worker(按 id 精确领取)。
 * 本测试复现 E2E 场景:入队一个 DRAFT_QUESTION run,交给生产 {@link RunWorker} 轮询器
 * 领取并执行,然后断言 run 到达 COMPLETED 且产出了节点。不加 {@code @Transactional},
 * 让 run 真实提交、对后台轮询线程可见。
 *
 * 【隔离性】。这是唯一针对共享测试数据库运行真实 {@code RunWorkerPoller} 的套件。
 * Spring 会缓存测试 ApplicationContext,若不在此关闭上下文,{@code @Scheduled} 轮询器
 * 会在 JVM 剩余生命周期里继续运行,可能领取/执行无关测试入队的 run(并在那些测试清理
 * 项目时提交 {@code context_snapshots} 行——在评估清理阶段表现为偶发的外键违规)。
 * {@code @DirtiesContext} AFTER_CLASS 保证本类结束后立即关闭上下文(及其调度器)。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spec.agent.brain.worker.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DraftQuestionAsyncPollerIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private RunService runService;
    @Autowired
    private RunWorker runWorker;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private AgentRunEventService eventService;

    @Test
    void draftQuestionRunReachesCompletedViaBackgroundPoller() throws Exception {
        Project project = projectService.createProject("Async draft reproduction");
        AgentRun enqueued = runService.createQueuedDraftQuestion(project.id());

        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        AgentRun run = null;
        while (Instant.now().isBefore(deadline)) {
            run = agentRunService.getRun(enqueued.id()).orElse(null);
            if (run != null
                    && (run.status() == AgentRunStatus.COMPLETED
                        || run.status() == AgentRunStatus.FAILED)) {
                break;
            }
            Thread.sleep(500);
        }

        assertThat(run).isNotNull();
        if (run.status() == AgentRunStatus.FAILED) {
            List<AgentRunEvent> events = eventService.findByRunId(enqueued.id());
            String trace = events.stream()
                    .reduce((first, second) -> second)
                    .map(e -> e.phase().code() + ":" + e.eventType())
                    .orElse("<no-events>");
            assertThat(run.status())
                    .describedAs("DRAFT_QUESTION run must complete; failed at " + trace)
                    .isEqualTo(AgentRunStatus.COMPLETED);
        }
        assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(run.producedNodeId()).isNotNull();
    }
}
