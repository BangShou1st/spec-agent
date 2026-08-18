package com.specagent.model.gateway;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.AgentTaskType;
import com.specagent.agent.FakeAgentOrchestrator;
import com.specagent.agent.FakeAgentRunResult;
import com.specagent.agent.FakeAnswerRunResult;
import com.specagent.agent.FakeSpecRunResult;
import com.specagent.agent.contracts.ReflectionResult;
import com.specagent.agent.gates.SpecSourceReferenceGuard;
import com.specagent.answer.Answer;
import com.specagent.answer.AnswerRepository;
import com.specagent.answer.AnswerService;
import com.specagent.credential.CredentialStatus;
import com.specagent.credential.OpenCodeCredentialService;
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
import com.specagent.spec.SourceReference;
import com.specagent.spec.SpecSnapshot;
import com.specagent.spec.SpecSnapshotService;
import com.specagent.model.provider.OpenCodeModelCatalog;
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
 * model: create project, {@link FakeAgentOrchestrator#draftNextQuestion}, answer
 * two nodes with deterministic domain-neutral answers,
 * {@link FakeAgentOrchestrator#answerActiveNodeAndDraftNext}, and
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
    private OpenCodeCredentialService credentialService;
    @Autowired
    private OpenCodeModelCatalog catalog;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private FakeAgentOrchestrator orchestrator;
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

    @Test
    void realFullLoopQuestionAnswerPatchNextNodeSpec() throws Exception {
        System.out.println("=== OpenCodeZenRealFullLoopSmokeTest: explicit live full loop (public OpenCode allowed) ===");
        String apiKey = System.getenv("SPEC_AGENT_OPENCODE_KEY");
        String resolved = seedCredential(apiKey);

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

        // 1. First real DRAFT_NODE creates a persisted root node.
        FakeAgentRunResult first = orchestrator.draftNextQuestion(project.id());
        assertRunCompleted(first.run());
        Node firstNode = first.producedNode();
        assertThat(nodeService.getNode(firstNode.id())).isPresent();
        assertThat(firstNode.question()).isNotBlank();
        assertThat(firstNode.purpose()).isNotNull();
        assertThat(firstNode.options()).allMatch(option -> option.id() != null);
        Route route = routeService.getRoute(project.activeRouteId()).orElseThrow();
        assertThat(route.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
        assertThat(route.rootNodeId()).isEqualTo(firstNode.id());
        assertThat(route.tipNodeId()).isEqualTo(firstNode.id());
        traceContains(first.run(), "model_called:DRAFT_NODE", "reflected:NODE", "persisted_node", "completed");
        System.out.println("draft first question: PASS");

        // 2. Real answer flow: answer node 1, interpret, draft and persist
        //    grounded patch, then draft next node.
        FakeAnswerRunResult answerOne = orchestrator.answerActiveNodeAndDraftNext(project.id(), ANSWER_ONE);
        assertRunCompleted(answerOne.run());
        assertAnswerLoop(project, firstNode, answerOne, ANSWER_ONE);
        System.out.println("answer + interpretation: PASS");

        // 3. Post-answer real DRAFT_NODE creates the next node; answer that
        //    node too so the loop covers two answer cycles.
        Node secondNode = answerOne.producedNode();
        FakeAnswerRunResult answerTwo = orchestrator.answerActiveNodeAndDraftNext(project.id(), ANSWER_TWO);
        assertRunCompleted(answerTwo.run());
        assertAnswerLoop(project, secondNode, answerTwo, ANSWER_TWO);
        System.out.println("post-answer next question: PASS");

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
        assertThat(finalRoute.tipNodeId()).isEqualTo(answerTwo.producedNode().id());

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

    private void assertAnswerLoop(Project project, Node answeredNode, FakeAnswerRunResult result, String answerText) {
        // Answer persisted immutably with the exact text the user gave.
        Answer answer = answerService.getAnswer(result.answer().id()).orElseThrow();
        assertThat(answer.nodeId()).isEqualTo(answeredNode.id());
        assertThat(answer.freeText()).isEqualTo(answerText);

        // Every step of the answer loop really ran against the model.
        assertThat(result.interpretResponse().taskType()).isEqualTo(AgentTaskType.INTERPRET_ANSWER);
        assertThat(result.patchResponse().taskType()).isEqualTo(AgentTaskType.DRAFT_ANSWER_PATCH);
        assertThat(result.nodeResponse().taskType()).isEqualTo(AgentTaskType.DRAFT_NODE);

        // Patch persisted; claims carry runtime-owned ids and, when confirmed,
        // the real answered node and answer ids as provenance.
        AnswerPatch patch = answerPatchService.getPatch(result.patch().id()).orElseThrow();
        assertThat(patch.sourceNodeId()).isEqualTo(answeredNode.id());
        assertThat(patch.sourceAnswerId()).isEqualTo(answer.id());
        assertThat(patch.claims()).isNotEmpty();
        for (Claim claim : patch.claims()) {
            assertThat(claim.id()).as("claim id is runtime-owned").isNotNull();
            if (claim.status() == ClaimStatus.CONFIRMED) {
                assertThat(claim.sourceNodeId()).isEqualTo(answeredNode.id());
                assertThat(claim.sourceAnswerId()).isEqualTo(answer.id());
            } else {
                assertThat(claim.sourceNodeId()).as("non-confirmed claims are not grounded by the model")
                        .isNull();
                assertThat(claim.sourceAnswerId()).isNull();
            }
        }

        // Next node persisted as a child of the answered node.
        Node nextNode = result.producedNode();
        assertThat(nextNode).isNotNull();
        assertThat(nodeService.getNode(nextNode.id())).isPresent();
        assertThat(nextNode.parentNodeId()).isEqualTo(answeredNode.id());

        // Route tip advanced; route stays open; the run owns all produced ids.
        Route route = routeService.getRoute(project.activeRouteId()).orElseThrow();
        assertThat(route.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
        assertThat(route.tipNodeId()).isEqualTo(nextNode.id());
        assertThat(result.run().producedAnswerId()).isEqualTo(answer.id());
        assertThat(result.run().producedPatchId()).isEqualTo(patch.id());
        assertThat(result.run().producedNodeId()).isEqualTo(nextNode.id());
        traceContains(result.run(), "model_called:INTERPRET_ANSWER", "model_called:DRAFT_ANSWER_PATCH",
                "model_called:DRAFT_NODE", "reflected:PATCH", "reflected:NODE",
                "persisted_answer", "persisted_patch", "persisted_node", "completed");
    }

    private void assertRunCompleted(AgentRun run) {
        assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(run.completedAt()).isNotNull();
    }

    private void traceContains(AgentRun run, String... steps) {
        AgentRun persisted = agentRunService.getRun(run.id()).orElseThrow();
        assertThat(persisted.trace()).contains(steps);
    }

    private String seedCredential(String apiKey) {
        CredentialStatus status = credentialService.save(apiKey);
        assertThat(status.configured()).isTrue();
        assertThat(status.masked()).isEqualTo("••••" + apiKey.substring(apiKey.length() - 4));
        assertThat(status.masked()).doesNotContain(apiKey);
        System.out.println("credential configured: yes, masked: " + status.masked());

        String resolved = credentialService.resolveOpenCode().orElseThrow();
        assertThat(resolved).isEqualTo(apiKey);
        System.out.println("credential resolved for OpenCode gateway: yes");
        return resolved;
    }
}