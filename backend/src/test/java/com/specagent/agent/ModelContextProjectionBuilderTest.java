package com.specagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.contracts.AnswerInterpretationResult;
import com.specagent.agent.AnswerCycleTestDriver;
import com.specagent.answer.Answer;
import com.specagent.answer.AnswerService;
import com.specagent.context.ContextSnapshot;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.patch.AnswerPatch;
import com.specagent.patch.AnswerPatchService;
import com.specagent.patch.Claim;
import com.specagent.patch.ClaimKind;
import com.specagent.patch.ClaimStatus;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.RegenerateResult;
import com.specagent.route.Route;
import com.specagent.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The model input envelope is built from the frozen snapshot's inclusion
 * manifest only: sibling routes and regeneration-excluded content never leak
 * into the projection, requirement state is derived via buildForContext, and
 * runtime-owned claim ids never leave the runtime.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ModelContextProjectionBuilderTest {

    private static final String SIBLING_SECRET_SENTINEL = "SIBLING_SECRET_SENTINEL_content";
    private static final String REGENERATE_SECRET_ANSWER = "REGENERATE_SECRET_ANSWER_content";
    private static final String REGENERATE_SECRET_CLAIM = "REGENERATE_SECRET_CLAIM_content";

    @Autowired
    private ProjectService projectService;
    @Autowired
    private FakeAgentOrchestrator fakeAgentOrchestrator;
    @Autowired
    private AnswerCycleTestDriver answerDriver;
    @Autowired
    private RouteService routeService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private AnswerPatchService answerPatchService;
    @Autowired
    private ModelContextProjectionBuilder projectionBuilder;
    @Autowired
    private com.specagent.context.ContextBuilder contextBuilder;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void initialDraftEnvelopeCarriesSnapshotIdentityAndEmptyLineage() throws Exception {
        Project project = projectService.createProject("Initial projection project");
        FakeAgentRunResult first = fakeAgentOrchestrator.draftNextQuestion(project.id());
        ContextSnapshot snapshot = first.contextSnapshot();

        String inputJson = projectionBuilder.buildInputJson(snapshot, projectionBuilder.initialNodeTaskInput());
        JsonNode envelope = mapper.readTree(inputJson);

        assertThat(envelope.has("context")).isTrue();
        assertThat(envelope.has("taskInput")).isTrue();
        assertThat(envelope.get("taskInput").get("mode").asText()).isEqualTo("initial");

        JsonNode context = envelope.get("context");
        assertThat(context.get("snapshotId").asText()).isEqualTo(snapshot.id().toString());
        assertThat(context.get("projectId").asText()).isEqualTo(project.id().toString());
        assertThat(context.get("routeId").asText()).isEqualTo(snapshot.routeId().toString());
        // The context is frozen before the first node exists, so the tip is
        // still unset and the lineage is empty in this initial run.
        assertThat(context.get("tipNodeId").isNull()).isTrue();
        assertThat(context.get("operationType").asText()).isEqualTo(snapshot.operationType().code());
        assertThat(context.get("contextHash").asText()).isEqualTo(snapshot.contextHash());
        assertThat(context.get("specialInputs")).isNotNull();
        assertThat(context.get("specialInputs").get("projectTitle").asText())
                .isEqualTo(project.title());
        assertThat(context.get("lineage")).isEmpty();

        assertThat(context.get("allowedSourceRefs"))
                .extracting(node -> node.asText())
                .contains("context:" + snapshot.id(), "route:" + snapshot.routeId());
    }

    @Test
    void answeredLineageCarriesAnswerPatchesAndRequirementStateWithoutClaimIds() throws Exception {
        Project project = projectService.createProject("Answered projection project");
        fakeAgentOrchestrator.draftNextQuestion(project.id());
        var answered = answerDriver.submitFreeText(project.id(), "the clarified requirement");
        // The next draft run's snapshot freezes the lineage that now includes
        // the first answer and its patch.
        FakeAgentRunResult nextDraft = fakeAgentOrchestrator.draftNextQuestion(project.id());
        ContextSnapshot snapshot = nextDraft.contextSnapshot();
        Answer answer = answerService.getAnswer(answered.answerId()).orElseThrow();
        AnswerPatch patch = answerPatchService.getPatch(answered.patchId()).orElseThrow();

        JsonNode context = mapper.readTree(projectionBuilder.buildInputJson(snapshot, Map.of()))
                .get("context");
        assertThat(context.get("lineage")).hasSize(2);
        JsonNode answerNode = context.get("lineage").get(0).get("answer");

        assertThat(answerNode.get("id").asText()).isEqualTo(answer.id().toString());
        assertThat(answerNode.get("nodeId").asText()).isEqualTo(answer.nodeId().toString());
        assertThat(answerNode.get("selectedOptionId").isNull()).isTrue();
        assertThat(answerNode.get("freeText").asText()).isEqualTo("the clarified requirement");
        assertThat(answerNode.has("createdAt")).isFalse();
        assertThat(answerNode.has("createdByUser")).isFalse();

        JsonNode patches = context.get("lineage").get(0).get("patches");
        assertThat(patches).hasSize(1);
        assertThat(patches.get(0).get("id").asText()).isEqualTo(patch.id().toString());
        assertThat(patches.get(0).get("claims")).isNotEmpty();
        JsonNode claim = patches.get(0).get("claims").get(0);
        assertThat(claim.has("kind")).isTrue();
        assertThat(claim.has("text")).isTrue();
        assertThat(claim.has("status")).isTrue();
        assertThat(claim.has("confidence")).isTrue();
        // Runtime-owned claim id never leaves the runtime.
        assertThat(claim.has("id")).isFalse();

        JsonNode requirementState = context.get("requirementState");
        assertThat(requirementState.get("claims")).isNotEmpty();
        assertThat(requirementState.get("claims").get(0).has("id")).isFalse();
        assertThat(requirementState.get("claims").get(0).get("kind").asText()).isIn(
                "goal", "stakeholder", "scope", "constraint", "success_criterion",
                "output_expectation", "risk", "assumption", "open_question", "conflict", "other");
    }

    @Test
    void taskInputCarriesExplicitRunLocalData() {
        Project project = projectService.createProject("Task input project");
        fakeAgentOrchestrator.draftNextQuestion(project.id());
        var answered = answerDriver.submitFreeText(project.id(), "free text answer");
        Answer answer = answerService.getAnswer(answered.answerId()).orElseThrow();
        AnswerPatch patch = answerPatchService.getPatch(answered.patchId()).orElseThrow();

        Map<String, Object> answerInput = projectionBuilder.answerTaskInput(answer);
        assertThat(answerInput).containsKeys("answer");
        assertThat((Map<String, Object>) answerInput.get("answer"))
                .containsKeys("id", "nodeId", "selectedOptionId", "freeText");

        AnswerInterpretationResult interpretation = new AnswerInterpretationResult(
                List.of("confirmed a"), List.of(), List.of(), List.of());
        Map<String, Object> patchInput = projectionBuilder.interpretationTaskInput(answer, interpretation);
        assertThat(patchInput).containsKeys("answer", "interpretation");
        assertThat((Map<String, Object>) patchInput.get("interpretation"))
                .containsKeys("confirmedTexts", "assumedTexts", "unresolvedTexts", "conflictTexts");

        Map<String, Object> afterAnswerInput = projectionBuilder.afterAnswerNodeTaskInput(answer, patch);
        assertThat(afterAnswerInput.get("mode")).isEqualTo("after_answer");
        assertThat(afterAnswerInput).containsKeys("answer", "acceptedPatch");
        assertThat((Map<String, Object>) afterAnswerInput.get("acceptedPatch"))
                .containsKeys("id", "claims");
    }

    @Test
    void projectionExcludesSiblingRouteContent() throws Exception {
        Project project = projectService.createProject("Sibling projection project");
        UUID originalRouteId = project.activeRouteId();
        FakeAgentRunResult first = fakeAgentOrchestrator.draftNextQuestion(project.id());
        UUID node1 = first.producedNode().id();

        answerService.finalizeAnswer(
                project.id(), originalRouteId, node1, null, "main route answer", "user");

        Route forkRoute = routeService.forkFromNode(project.id(), originalRouteId, node1, "sibling route");
        Node siblingNode = nodeService.createChildNode(project.id(), forkRoute.id(), node1,
                "Sibling question?", "sibling purpose", List.of(), true);
        Answer siblingAnswer = answerService.finalizeAnswer(
                project.id(), forkRoute.id(), siblingNode.id(), null,
                SIBLING_SECRET_SENTINEL, "user");
        answerPatchService.save(
                project.id(), forkRoute.id(), siblingNode.id(), siblingAnswer.id(),
                List.of(Claim.of(ClaimKind.OTHER, SIBLING_SECRET_SENTINEL + "_claim",
                        ClaimStatus.CONFIRMED, siblingNode.id(), siblingAnswer.id())),
                null);

        routeService.setActiveRoute(project.id(), originalRouteId);
        FakeAgentRunResult result = fakeAgentOrchestrator.draftNextQuestion(project.id());

        String inputJson = projectionBuilder.buildInputJson(
                result.contextSnapshot(), projectionBuilder.initialNodeTaskInput());
        assertThat(inputJson).doesNotContain(SIBLING_SECRET_SENTINEL);
        assertThat(inputJson).doesNotContain(siblingNode.question());
        assertThat(inputJson).doesNotContain(siblingAnswer.id().toString());
    }

    @Test
    void regenerateProjectionKeepsParentLineageAndForbidsOldContent() throws Exception {
        Project project = projectService.createProject("Regenerate projection project");
        fakeAgentOrchestrator.draftNextQuestion(project.id());
        var firstAnswer = answerDriver.submitFreeText(project.id(), "first requirement");
        UUID node1 = answerService.getAnswer(firstAnswer.answerId()).orElseThrow().nodeId();
        var secondAnswer = answerDriver.submitFreeText(project.id(), REGENERATE_SECRET_ANSWER);
        UUID node2 = answerService.getAnswer(secondAnswer.answerId()).orElseThrow().nodeId();
        UUID oldAnswerId = secondAnswer.answerId();
        UUID oldPatchId = secondAnswer.patchId();

        // Regenerate node2: node2's own answer/patch and the child subtree
        // below it must vanish from the projection; only the parent lineage
        // (node1 and its answer) remains, plus the allowed special inputs.
        RegenerateResult regenerated = routeService.regenerateFromNode(
                project.id(), secondAnswer.run().routeId(), node2, REGENERATE_SECRET_CLAIM,
                "What is the clarified outcome?", "clarifies", List.of());
        ContextSnapshot snapshot = regenerated.contextSnapshot();

        assertThat(snapshot.operationType().code()).isEqualTo("regenerate");
        String inputJson = projectionBuilder.buildInputJson(snapshot, Map.of());
        JsonNode context = mapper.readTree(inputJson).get("context");

        // Special inputs carry the target node's original question/purpose and
        // the user instruction; they are allowed regeneration inputs.
        assertThat(context.get("specialInputs").get("oldQuestion").asText())
                .isEqualTo(node2Question());
        assertThat(context.get("specialInputs").get("oldPurpose").asText()).isEqualTo(node2Purpose());
        assertThat(context.get("specialInputs").get("userInstruction").asText())
                .isEqualTo(REGENERATE_SECRET_CLAIM);

        // Old answer, old patch, and the child subtree are forbidden.
        assertThat(inputJson).doesNotContain(REGENERATE_SECRET_ANSWER);
        assertThat(inputJson).doesNotContain(oldAnswerId.toString());
        assertThat(inputJson).doesNotContain(oldPatchId.toString());
        assertThat(inputJson).doesNotContain(node2.toString());
        assertThat(context.get("lineage")).hasSize(1);
        assertThat(context.get("lineage").get(0).get("node").get("id").asText())
                .isEqualTo(node1.toString());
        // The surviving parent is the legacy-drafted root: its purpose keeps
        // round-tripping through the lineage projection.
        assertThat(context.get("lineage").get(0).get("node").get("purpose").asText())
                .isEqualTo(node1Purpose());
        assertThat(context.get("allowedSourceRefs"))
                .extracting(node -> node.asText())
                .doesNotContain("node:" + node2, "answer:" + oldAnswerId, "patch:" + oldPatchId);
    }

    @Test
    void projectionNeverContainsRuntimeMetadata() throws Exception {
        Project project = projectService.createProject("Metadata-free projection project");
        fakeAgentOrchestrator.draftNextQuestion(project.id());
        var answered = answerDriver.submitFreeText(project.id(), "answer text");

        // A fresh snapshot from the active route covers the answered lineage.
        ContextSnapshot snapshot = contextBuilder.buildFromActiveRoute(
                project.id(), answered.run().id(), com.specagent.context.ContextOperationType.NORMAL);
        String inputJson = projectionBuilder.buildInputJson(snapshot, Map.of());

        assertThat(inputJson).doesNotContain("createdByUser");
        assertThat(inputJson).doesNotContain("createdAt");
        assertThat(inputJson).doesNotContain("created_by_run");
        assertThat(inputJson).doesNotContain("agentRun");
        assertThat(inputJson).doesNotContain("excludedRouteIds");
    }

    /**
     * The fake DRAFT_NODE model output is deterministic: node1 is the
     * legacy-drafted root and carries the fake purpose. node2 is drafted by
     * the decision runtime's REQUEST_USER_INPUT action, whose contract has no
     * purpose field, so its purpose round-trips as empty.
     */
    private String node2Question() {
        return "What is the most important outcome?";
    }

    private String node2Purpose() {
        return "";
    }

    private String node1Purpose() {
        return "This clarifies the primary requirement goal.";
    }
}
