package com.specagent.agent.runtime;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.AnswerCycleTestDriver;
import com.specagent.agent.contract.ActionFamily;
import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.agent.runevent.AgentRunEventRepository;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Cross-language exit gate for Stage A: the real Python agent-brain service
 * completes a deterministic DECISION through the Java internal inference
 * broker, and no provider key material appears anywhere in the exchanged
 * responses or persisted events.
 *
 * <p>Requires a running agent-brain on localhost:8100 in fake model mode and
 * the shared dev secret:
 *
 * <pre>
 * cd agent-brain &amp;&amp; .venv/Scripts/uvicorn spec_agent_brain.app:app --port 8100
 * </pre>
 *
 * <p>The test binds Spring to port 18081 so the brain's configured broker URL
 * ({@code http://localhost:18081/internal/v1/model-inference}) reaches this
 * process. When the brain is not running, the test is skipped so the default
 * suite stays green offline.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18081",
                "spec.agent.brain.engine=remote-python",
                "spec.agent.brain.base-url=http://localhost:8100"
        })
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PythonBrainCrossLanguageIntegrationTest {

    private static final String BRAIN_HEALTH = "http://localhost:8100/health";

    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private RunService runService;
    @Autowired
    private RunWorker worker;
    @Autowired
    private AnswerCycleTestDriver answerDriver;
    @Autowired
    private AgentRunEventRepository eventRepository;

    @BeforeAll
    void requireRunningBrain() {
        assumeTrue(brainReachable(),
                "agent-brain not reachable at " + BRAIN_HEALTH
                        + " — start it with uvicorn to run the cross-language gate");
    }

    @Test
    void pythonCompletesDeterministicDecisionThroughJavaBroker() {
        Project project = projectService.createProject("跨语言决策项目");

        AgentRun run = runService.createQueuedDraftQuestion(project.id());
        worker.executeRun(run);

        assertThat(runService.getRun(run.id()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);

        List<AgentRunEvent> events = eventRepository.findByRunId(run.id());

        // Worker lifecycle events record the exact phase progression. A pure
        // continuation is ONE DECISION — no STATE_UPDATE phase on this path.
        List<String> lifecycle = events.stream()
                .filter(event -> !event.eventType().equals("MODEL_INFERENCE"))
                .map(AgentRunEvent::eventType).toList();
        assertThat(lifecycle).containsExactly(
                "RUN_CREATED",
                "SNAPSHOT_BUILT",
                "DECISION_STARTED",
                "PROPOSAL_CREATED",
                "EXECUTING",
                "RUN_COMPLETED");
        assertThat(events.stream().map(AgentRunEvent::phase).distinct().toList())
                .containsExactly(
                        AgentRunPhase.CREATED,
                        AgentRunPhase.SNAPSHOT_BUILT,
                        AgentRunPhase.DECIDING,
                        AgentRunPhase.PROPOSAL_CREATED,
                        AgentRunPhase.EXECUTING,
                        AgentRunPhase.COMPLETED);

        // The brain call crossed the Java inference broker.
        List<String> brokerCallTypes = events.stream()
                .filter(event -> event.eventType().equals("MODEL_INFERENCE"))
                .map(event -> (String) event.payload().get("callType")).toList();
        assertThat(brokerCallTypes).containsExactly("DECISION");

        // The proposal came back through the full remote path, validated, and
        // auto-executed as a real question node at the tip.
        String proposalText = events.stream()
                .filter(event -> event.eventType().equals("PROPOSAL_CREATED"))
                .map(event -> event.payload().toString())
                .findFirst().orElse("");
        assertThat(proposalText).contains(ActionFamily.REQUEST_USER_INPUT.name());
        assertThat(runService.getRun(run.id()).orElseThrow().producedNodeId()).isNotNull();

        // Zero provider key material in any persisted event payload.
        String allPayloads = events.stream()
                .map(event -> event.payload().toString())
                .reduce("", (a, b) -> a + b);
        assertThat(allPayloads).doesNotContain("sk-").doesNotContain("apiKey");
    }

    /**
     * STATE_UPDATE still crosses the same broker on the answer cycle, keeping
     * the cross-language gate over both brain call types after the question
     * draft became a single-DECISION continuation.
     */
    @Test
    void pythonAnswerCycleCrossesTheBrokerWithStateUpdateAndDecision() {
        Project project = projectService.createProject("跨语言回答项目");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "最重要的目标是什么？", null, List.of(), true);

        UUID answerRunId = answerDriver.submitFreeText(project.id(), "明确首要目标")
                .run().id();
        assertThat(runService.getRun(answerRunId).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);

        List<AgentRunEvent> events = eventRepository.findByRunId(answerRunId);
        List<String> brokerCallTypes = events.stream()
                .filter(event -> event.eventType().equals("MODEL_INFERENCE"))
                .map(event -> event.payload().get("callType").toString()).toList();
        assertThat(brokerCallTypes).containsExactly("STATE_UPDATE", "DECISION");
        // The answered root kept its position: exactly one lineage node was
        // answered, and the cycle produced the next question node.
        assertThat(runService.getRun(answerRunId).orElseThrow().producedNodeId()).isNotNull();
        assertThat(nodeService.getNode(runService.getRun(answerRunId).orElseThrow()
                .producedNodeId()).orElseThrow().parentNodeId()).isEqualTo(root.id());
    }

    private boolean brainReachable() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(500)).build();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(BRAIN_HEALTH))
                            .timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception ex) {
            return false;
        }
    }
}
