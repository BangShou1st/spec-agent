package com.specagent.model.gateway;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.AgentTaskType;
import com.specagent.agent.FakeAgentOrchestrator;
import com.specagent.agent.FakeAnswerRunResult;
import com.specagent.agent.FakeSpecRunResult;
import com.specagent.agent.contracts.ReflectionResult;
import com.specagent.agent.gates.SpecSourceReferenceGuard;
import com.specagent.answer.Answer;
import com.specagent.answer.AnswerRepository;
import com.specagent.answer.AnswerService;
import com.specagent.node.Node;
import com.specagent.node.NodeRepository;
import com.specagent.node.NodeService;
import com.specagent.patch.AnswerPatch;
import com.specagent.patch.AnswerPatchService;
import com.specagent.patch.Claim;
import com.specagent.patch.ClaimStatus;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteService;
import com.specagent.settings.opencode.OpenCodeSettingsService;
import com.specagent.settings.opencode.OpenCodeSettingsStatus;
import com.specagent.spec.SourceReference;
import com.specagent.spec.SpecSnapshot;
import com.specagent.spec.SpecSnapshotService;
import com.specagent.model.provider.OpenCodeModelCatalog;
import com.specagent.support.LiveSmokeEnvironment;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explicit manual real full-loop smoke against the real OpenCode Zen API.
 *
 * <p>Gated on the {@code SPEC_AGENT_OPENCODE_KEY} environment variable and
 * never runs under {@code gradlew test} by default: automated tests must make
 * zero public OpenCode requests. Run it explicitly from the backend directory
 * after sourcing the local secrets file, e.g.:
 *
 * <pre>
 *   set -a; source .local-secrets.env; set +a
 *   ./gradlew.bat test --no-daemon --tests com.specagent.model.gateway.OpenCodeZenRealFullLoopSmokeTest
 * </pre>
 *
 * <p>The smoke drives the normal runtime public path end to end with the real
 * model: create project, a single DECISION question draft through the async
 * decision runtime ({@link com.specagent.agent.DecisionCycleTestDriver#draftQuestion}),
 * answer two nodes with deterministic domain-neutral answers,
 * two async ANSWER_CYCLE answer turns, and
 * {@link FakeAgentOrchestrator#generateSpec}. Every assertion targets runtime
 * structure, persistence, grounding and state transitions — never the model's
 * wording quality. {@code SPEC_AGENT_MODEL_GATEWAY=opencode} must be set so the
 * runtime actually resolves {@link OpenCodeZenModelGateway}.
 *
 * <p>The real key is only seeded into the encrypted credential store and never
 * printed; the transaction rolls back afterwards so no credential or project
 * data is left in the development database.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@EnabledIfEnvironmentVariable(named = "SPEC_AGENT_OPENCODE_KEY", matches = ".+")
class OpenCodeZenRealFullLoopSmokeTest {

    /** Domain-neutral answer 1: stated goals, users and expected output. */
    private static final String ANSWER_ONE = """
            We need a tool that turns rough product ideas into a clear requirement specification.
            The main users are small product teams.
            The output should include goals, scope, constraints, unresolved questions, and source-backed sections.""";

    /** Domain-neutral answer 2: scoping the first version. */
    private static final String ANSWER_TWO = """
            The first version should focus on backend requirement clarification only.
            Do not include collaboration, frontend design, code generation, or external document import yet.
            Success means the team can answer guided questions and receive a grounded draft specification.""";

    @Autowired
    private ApplicationContext context;
    @Autowired
    private OpenCodeSettingsService settingsService;
    @Autowired
    private OpenCodeModelCatalog catalog;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private FakeAgentOrchestrator orchestrator;
    @Autowired
    private com.specagent.agent.AnswerCycleTestDriver answerDriver;
    @Autowired
    private com.specagent.agent.DecisionCycleTestDriver draftDriver;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private NodeRepository nodeRepository;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private AnswerRepository answerRepository;
    @Autowired
    private AnswerPatchService answerPatchService;
    @Autowired
    private SpecSnapshotService specSnapshotService;
    @Autowired
    private SpecSourceReferenceGuard specSourceReferenceGuard;

    /**
     * The env gate already skipped the test when the key is missing; this
     * second gate diagnoses the other preconditions an operator must satisfy
     * (explicit {@code opencode} gateway selector, free selected model) so a
     * half-configured run can never be mistaken for a PASS.
     */
    @BeforeEach
    void checkLiveSmokeEnvironment() {
        LiveSmokeEnvironment.Readiness readiness = LiveSmokeEnvironment.check();
        readiness.print();
        Assumptions.assumeTrue(readiness.ready(), String.join("; ", readiness.blockers()));
    }

    @Test
    void realFullLoopQuestionAnswerPatchNextNodeSpec() throws Exception {
        System.out.println("=== OpenCodeZenRealFullLoopSmokeTest: explicit live full loop (public OpenCode allowed) ===");
        String apiKey = System.getenv("SPEC_AGENT_OPENCODE_KEY");
        String resolved = seedSettings(apiKey);

        // The runtime must actually resolve the OpenCode gateway through the
        // normal ModelGateway selection, not through a direct autowire.
        assertThat(context.getBean(ModelGateway.class)).isInstanceOf(OpenCodeZenModelGateway.class);
        System.out.println("gateway selector: opencode -> OpenCodeZenModelGateway");

        // Discover current free models from GET /models.
        List<String> freeModels = catalog.listFreeModels(resolved);
        assertThat(freeModels).isNotEmpty();
        assertThat(freeModels).allMatch(id -> id.endsWith("-free"));
        System.out.println("free model discovery count: " + freeModels.size());

        // The explicitly selected model must be among the current free models.
        String selected = System.getenv().getOrDefault("SPEC_AGENT_OPENCODE_MODEL", "mimo-v2.5-free");
        assertThat(freeModels).contains(selected);
        System.out.println("selected model: " + selected);

        Project project = projectService.createProject("Real full loop smoke project");

        // 1. First single-DECISION draft creates a persisted root node.
        AgentRun first = draftDriver.draftQuestion(project.id());
        assertRunCompleted(first);
        assertThat(first.producedNodeId()).isNotNull();
        Node firstNode = nodeService.getNode(first.producedNodeId()).orElseThrow();
        assertThat(firstNode.question()).isNotBlank();
        assertThat(firstNode.purpose()).isNotBlank();
        assertThat(firstNode.options()).allMatch(option -> option.id() != null);
        Route route = routeService.getRoute(project.activeRouteId()).orElseThrow();
        assertThat(route.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
        assertThat(route.rootNodeId()).isEqualTo(firstNode.id());
        assertThat(route.tipNodeId()).isEqualTo(firstNode.id());
        traceContains(first, "context_built", "executing", "completed");
        System.out.println("draft first question: PASS");

        // 2. Real answer flow: answer node 1, interpret, draft and persist
        //    grounded patch, then draft next node.
        var answerOne = answerDriver.submitFreeText(project.id(), ANSWER_ONE);
        assertRunCompleted(answerOne.run());
        System.out.println("answer cycle 1: PASS; answerId=" + answerOne.answerId());

        // 3. Answer the produced next node so the loop covers two cycles.
        Node secondNode = nodeService.getNode(answerOne.producedNodeId()).orElseThrow();
        var answerTwo = answerDriver.submitFreeText(project.id(), ANSWER_TWO);
        assertRunCompleted(answerTwo.run());
        System.out.println("answer cycle 2: PASS; answerId=" + answerTwo.answerId());

        // 4. Real DRAFT_SPEC creates a persisted, grounded spec snapshot.
        FakeSpecRunResult specResult = orchestrator.generateSpec(project.id());
        assertRunCompleted(specResult.run());
        SpecSnapshot snapshot = specResult.specSnapshot();
        assertThat(specSnapshotService.getSnapshot(snapshot.id())).isPresent();
        assertThat(snapshot.routeId()).isEqualTo(project.activeRouteId());
        assertThat(snapshot.contextSnapshotId()).isEqualTo(specResult.contextSnapshot().id());
        assertThat(snapshot.sections()).isNotEmpty();
        assertThat(snapshot.sections()).allSatisfy(section -> assertThat(section.content()).isNotBlank());
        assertThat(snapshot.unresolvedItems()).isNotNull();
        assertThat(snapshot.sourceRefs()).isNotEmpty();
        assertThat(snapshot.hasSourceReferences()).isTrue();

        // The runtime already ran SpecGroundingGate before persisting the
        // snapshot; re-run the source reference guard on the persisted artifact.
        ReflectionResult guard = specSourceReferenceGuard.validate(
                project.id(), project.activeRouteId(), specResult.contextSnapshot(), snapshot.sourceRefs());
        assertThat(guard.accepted()).as("persisted spec source refs must pass the source reference guard")
                .isTrue();

        // Every source reference must stay inside the frozen context: the
        // context snapshot, the route, or records the context actually included.
        Set<UUID> allowed = new HashSet<>();
        allowed.add(specResult.contextSnapshot().id());
        allowed.add(project.activeRouteId());
        allowed.addAll(specResult.contextSnapshot().includedNodeIds());
        allowed.addAll(specResult.contextSnapshot().includedAnswerIds());
        allowed.addAll(specResult.contextSnapshot().includedPatchIds());
        for (SourceReference ref : snapshot.sourceRefs()) {
            assertThat(allowed)
                    .as("source ref %s:%s must point inside the frozen context", ref.kind(), ref.refId())
                    .contains(ref.refId());
        }
        traceContains(specResult.run(), "model_called:DRAFT_SPEC", "reflected:SPEC_GROUNDING",
                "reflected:SOURCE_REFERENCES", "persisted_spec_snapshot", "completed");
        System.out.println("generate spec: PASS");

        // 5. Final route coherence: still OPEN, root unchanged, tip at the
        //    latest node.
        Route finalRoute = routeService.getRoute(project.activeRouteId()).orElseThrow();
        assertThat(finalRoute.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
        assertThat(finalRoute.rootNodeId()).isEqualTo(firstNode.id());
        assertThat(finalRoute.tipNodeId()).isEqualTo(answerTwo.producedNodeId());

        System.out.println("SpecGroundingGate: PASS (runtime gate passed before snapshot persist)");
        System.out.println("SpecSourceReferenceGuard: PASS (re-validated over persisted snapshot)");
        List<UUID> allNodeIds = nodeRepository.findByProject(project.id()).stream().map(Node::id).toList();
        System.out.println("artifacts: nodes=" + allNodeIds.size()
                + "; answers=" + answerRepository.findByRouteAndNodeIds(project.activeRouteId(), allNodeIds).size()
                + "; patches=" + answerPatchService.findByRoute(project.activeRouteId()).size()
                + "; specSnapshots=" + specSnapshotService.listByRoute(project.activeRouteId()).size());
        System.out.println("route: status=" + finalRoute.lifecycleStatus().code()
                + "; root=" + finalRoute.rootNodeId() + "; tip=" + finalRoute.tipNodeId());
    }

    private void assertRunCompleted(AgentRun run) {
        assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(run.completedAt()).isNotNull();
    }

    private void traceContains(AgentRun run, String... steps) {
        AgentRun persisted = agentRunService.getRun(run.id()).orElseThrow();
        assertThat(persisted.trace()).contains(steps);
    }

    private String seedSettings(String apiKey) {
        String selectedModel = System.getenv().getOrDefault("SPEC_AGENT_OPENCODE_MODEL", "mimo-v2.5-free");
        OpenCodeSettingsStatus status = settingsService.save(apiKey, selectedModel);
        assertThat(status.configured()).isTrue();
        assertThat(status.maskedKey()).isEqualTo("••••" + apiKey.substring(apiKey.length() - 4));
        assertThat(status.maskedKey()).doesNotContain(apiKey);
        System.out.println("OpenCode settings configured: yes, masked: " + status.maskedKey());
        return apiKey;
    }
}
