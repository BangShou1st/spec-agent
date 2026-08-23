package com.specagent.model.gateway;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.FakeAgentOrchestrator;
import com.specagent.agent.FakeAnswerRunResult;
import com.specagent.agent.FakeSpecRunResult;
import com.specagent.agent.ModelRequest;
import com.specagent.agent.contracts.ReflectionResult;
import com.specagent.agent.gates.SpecSourceReferenceGuard;
import com.specagent.model.provider.OpenCodeModelCatalog;
import com.specagent.node.Node;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteService;
import com.specagent.spec.SourceReference;
import com.specagent.spec.SpecSnapshot;
import com.specagent.support.LiveSmokeEnvironment;
import com.specagent.settings.opencode.OpenCodeSettingsService;
import com.specagent.settings.opencode.OpenCodeSettingsStatus;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Explicit live smoke: real OpenCode model under route isolation.
 *
 * <p>Env-gated like the other live smokes and {@code @Transactional}
 * (credential seeded in the test transaction is rolled back). The gateway is
 * selected explicitly through the normal {@link ModelGateway} wiring while
 * every real {@link ModelRequest} the orchestrator sends is captured by a spy;
 * the real gateway still handles each call. The test then proves the
 * envelope-level isolation the runtime promised: sibling route sentinels and
 * superseded record ids never appear in the fork route's model input, and the
 * spec stays grounded inside the frozen fork context.
 *
 * <p>No raw prompt or full model input is ever printed; assertions only check
 * contains / doesNotContain.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spec.agent.model.gateway=opencode")
@Transactional
@EnabledIfEnvironmentVariable(named = "SPEC_AGENT_OPENCODE_KEY", matches = ".+")
class OpenCodeZenRouteIsolationSmokeTest {

    private static final String SIBLING_SENTINEL = "SIBLING_SENTINEL_DO_NOT_LEAK_7f3a";
    private static final String FORK_ANSWER = "The fork branch wants hourly progress tracking only.";

    @Autowired
    private ApplicationContext context;
    @Autowired
    private OpenCodeSettingsService settingsService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private FakeAgentOrchestrator orchestrator;
    @Autowired
    private com.specagent.agent.AnswerCycleTestDriver answerDriver;
    @Autowired
    private com.specagent.node.NodeService nodeService;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private OpenCodeModelCatalog catalog;
    @Autowired
    private SpecSourceReferenceGuard specSourceReferenceGuard;

    @SpyBean
    private OpenCodeZenModelGateway gateway;

    private final List<ModelRequest> captured = new ArrayList<>();

    @BeforeEach
    void setUp() {
        LiveSmokeEnvironment.Readiness readiness = LiveSmokeEnvironment.check();
        readiness.print();
        Assumptions.assumeTrue(readiness.ready(), String.join("; ", readiness.blockers()));

        captured.clear();
        doAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return invocation.callRealMethod();
        }).when(gateway).run(any(ModelRequest.class));
    }

    @Test
    void realModelForkIsolationAndGroundedForkSpec() throws Exception {
        System.out.println("=== OpenCodeZenRouteIsolationSmokeTest: explicit route isolation (public OpenCode allowed) ===");
        String apiKey = System.getenv("SPEC_AGENT_OPENCODE_KEY");
        String resolved = seedSettings(apiKey);

        // The runtime must resolve the OpenCode gateway through the normal
        // ModelGateway selection; the spy only captures, the real gateway runs.
        assertThat(context.getBean(ModelGateway.class)).isInstanceOf(OpenCodeZenModelGateway.class);
        System.out.println("gateway selector: opencode -> OpenCodeZenModelGateway");

        List<String> freeModels = catalog.listFreeModels(resolved);
        assertThat(freeModels).isNotEmpty();
        assertThat(freeModels).allMatch(id -> id.endsWith("-free"));
        System.out.println("free model discovery count: " + freeModels.size());
        String selected = System.getenv().getOrDefault("SPEC_AGENT_OPENCODE_MODEL", "mimo-v2.5-free");
        assertThat(freeModels).contains(selected);
        System.out.println("selected model: " + selected);

        Project project = projectService.createProject("Real route isolation smoke");

        // Route R1: root -> A -> A2. The answer on A carries the sibling
        // sentinel that must never reach the fork route's model input.
        Node root = orchestrator.draftNextQuestion(project.id()).producedNode();
        var rootRun = answerDriver.submitFreeText(project.id(), "Root answer: tracking workflow progress.");
        com.specagent.node.Node a = nodeService.getNode(rootRun.producedNodeId()).orElseThrow();
        var siblingRun = answerDriver.submitFreeText(project.id(), SIBLING_SENTINEL + " sibling branch preference");
        com.specagent.node.Node a2 = nodeService.getNode(siblingRun.producedNodeId()).orElseThrow();
        UUID r1RouteId = rootRun.run().routeId();

        // Fork from the root; the fork route becomes active.
        Route fork = routeService.forkFromNode(project.id(), r1RouteId, root.id(), "Fork at root");
        UUID r2RouteId = fork.id();
        System.out.println("fork: R1=" + r1RouteId + " -> R2=" + r2RouteId);

        int forkPoint = captured.size();
        var forkRun = answerDriver.submitFreeText(project.id(), FORK_ANSWER);
        Node b = nodeService.getNode(forkRun.producedNodeId()).orElseThrow();
        assertThat(b.parentNodeId()).isEqualTo(root.id());
        System.out.println("fork answer loop: PASS; requests: "
                + (captured.size() - forkPoint) + " (interpret, patch, draft node)");

        // Envelope-level isolation of the fork requests.
        for (ModelRequest request : captured.subList(forkPoint, captured.size())) {
            assertThat(request.inputJson())
                    .as("fork request for task %s must exclude sibling content", request.taskType())
                    .doesNotContain(SIBLING_SENTINEL)
                    .doesNotContain("node:" + a.id())
                    .doesNotContain("node:" + a2.id())
                    .doesNotContain("answer:" + rootRun.answerId())
                    .doesNotContain("route:" + r1RouteId)
                    .contains("node:" + root.id());
        }
        System.out.println("sibling exclusion: PASS (sibling sentinel and R1 ids absent from all fork model inputs)");

        // Spec on the fork route, grounded in the frozen fork context only.
        FakeSpecRunResult specResult = orchestrator.generateSpec(project.id());
        assertThat(specResult.run().status()).isEqualTo(AgentRunStatus.COMPLETED);
        SpecSnapshot snapshot = specResult.specSnapshot();
        assertThat(snapshot.routeId()).isEqualTo(r2RouteId);
        assertThat(snapshot.contextSnapshotId()).isEqualTo(specResult.contextSnapshot().id());
        assertThat(snapshot.sourceRefs()).isNotEmpty();

        Set<UUID> allowed = new HashSet<>();
        allowed.add(specResult.contextSnapshot().id());
        allowed.add(r2RouteId);
        allowed.addAll(specResult.contextSnapshot().includedNodeIds());
        allowed.addAll(specResult.contextSnapshot().includedAnswerIds());
        allowed.addAll(specResult.contextSnapshot().includedPatchIds());
        for (SourceReference ref : snapshot.sourceRefs()) {
            assertThat(allowed)
                    .as("source ref %s:%s must point inside the frozen fork context", ref.kind(), ref.refId())
                    .contains(ref.refId());
        }
        ReflectionResult guard = specSourceReferenceGuard.validate(
                project.id(), r2RouteId, specResult.contextSnapshot(), snapshot.sourceRefs());
        assertThat(guard.accepted()).isTrue();
        System.out.println("generate spec on fork route: PASS; source refs: " + snapshot.sourceRefs().size());

        // Trace of the fork-route runs stays free of the excluded sentinel.
        List<AgentRun> runs = agentRunService.listByProject(project.id());
        for (AgentRun run : runs.subList(runs.size() - 2, runs.size())) {
            assertThat(run.trace()).doesNotContain(SIBLING_SENTINEL);
        }
        System.out.println("trace safety: PASS; total model requests: " + captured.size()
                + "; masked key: \u2022\u2022\u2022\u2022" + apiKey.substring(apiKey.length() - 4));
    }

    private String seedSettings(String apiKey) {
        String selectedModel = System.getenv().getOrDefault("SPEC_AGENT_OPENCODE_MODEL", "mimo-v2.5-free");
        OpenCodeSettingsStatus status = settingsService.save(apiKey, selectedModel);
        assertThat(status.configured()).isTrue();
        assertThat(status.maskedKey()).isEqualTo("\u2022\u2022\u2022\u2022" + apiKey.substring(apiKey.length() - 4));
        assertThat(status.maskedKey()).doesNotContain(apiKey);
        System.out.println("OpenCode settings configured: yes, masked: " + status.maskedKey());
        return apiKey;
    }
}
