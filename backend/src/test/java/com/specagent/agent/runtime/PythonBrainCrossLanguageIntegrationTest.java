package com.specagent.agent.runtime;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.contract.ActionFamily;
import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.agent.runevent.AgentRunEventRepository;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
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
 * <p>The test binds Spring to port 18080 so the brain's configured broker URL
 * ({@code http://localhost:18080/internal/v1/model-inference}) reaches this
 * process. When the brain is not running, the test is skipped so the default
 * suite stays green offline.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18080",
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
        nodeService.createRootNode(project.id(), project.activeRouteId(),
                "最重要的目标是什么？", null, List.of(), true);

        AgentRun run = runService.createQueuedRun(project.id());
        worker.executeRun(run);

        assertThat(runService.getRun(run.id()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);

        List<AgentRunEvent> events = eventRepository.findByRunId(run.id());

        // Worker lifecycle events record the exact phase progression.
        List<String> lifecycle = events.stream()
                .filter(event -> !event.eventType().equals("MODEL_INFERENCE"))
                .map(AgentRunEvent::eventType).toList();
        assertThat(lifecycle).containsExactly(
                "RUN_CREATED",
                "SNAPSHOT_BUILT",
                "STATE_UPDATE_STARTED",
                "STATE_UPDATE_COMPLETED",
                "DECISION_STARTED",
                "PROPOSAL_CREATED",
                "RUN_COMPLETED");
        assertThat(events.stream().map(AgentRunEvent::phase).distinct().toList())
                .containsExactly(
                        AgentRunPhase.CREATED,
                        AgentRunPhase.SNAPSHOT_BUILT,
                        AgentRunPhase.STATE_UPDATING,
                        AgentRunPhase.STATE_UPDATED,
                        AgentRunPhase.DECIDING,
                        AgentRunPhase.PROPOSAL_CREATED,
                        AgentRunPhase.COMPLETED);

        // Both brain calls crossed the Java inference broker.
        List<String> brokerCallTypes = events.stream()
                .filter(event -> event.eventType().equals("MODEL_INFERENCE"))
                .map(event -> (String) event.payload().get("callType")).toList();
        assertThat(brokerCallTypes).containsExactly("STATE_UPDATE", "DECISION");

        // The proposal came back through the full remote path, validated.
        String proposalText = events.stream()
                .filter(event -> event.eventType().equals("PROPOSAL_CREATED"))
                .map(event -> event.payload().toString())
                .findFirst().orElse("");
        assertThat(proposalText).contains(ActionFamily.REQUEST_USER_INPUT.name());

        // Zero provider key material in any persisted event payload.
        String allPayloads = events.stream()
                .map(event -> event.payload().toString())
                .reduce("", (a, b) -> a + b);
        assertThat(allPayloads).doesNotContain("sk-").doesNotContain("apiKey");
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
