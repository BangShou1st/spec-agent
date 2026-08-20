package com.specagent.model.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.AgentAction;
import com.specagent.agent.AgentTaskType;
import com.specagent.agent.ModelRequest;
import com.specagent.agent.ModelResponse;
import com.specagent.agent.StructuredOutputMapper;
import com.specagent.agent.contracts.AnswerInterpretationResult;
import com.specagent.agent.contracts.AnswerPatchDraft;
import com.specagent.agent.contracts.NodeDraft;
import com.specagent.agent.contracts.SpecDraft;
import com.specagent.agent.gates.SpecGroundingGate;
import com.specagent.model.contract.StructuredModelOutputParser;
import com.specagent.model.provider.OpenCodeModelCatalog;
import com.specagent.settings.opencode.OpenCodeSettingsService;
import com.specagent.settings.opencode.OpenCodeSettingsStatus;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explicit manual live smoke against the real OpenCode Zen API.
 *
 * <p>This test is gated on the {@code SPEC_AGENT_OPENCODE_KEY} environment
 * variable and never runs under {@code gradlew test} by default: automated
 * tests must make zero public OpenCode requests. Run it explicitly, e.g.:
 *
 * <pre>
 *   SPEC_AGENT_MODEL_GATEWAY=opencode SPEC_AGENT_OPENCODE_KEY=... \
 *   SPEC_AGENT_OPENCODE_MODEL=... gradlew.bat test \
 *       --tests "com.specagent.model.gateway.OpenCodeZenLiveSmokeTest"
 * </pre>
 *
 * <p>The {@code SPEC_AGENT_MODEL_GATEWAY=opencode} switch is required: the
 * smoke proves the real runtime wiring resolves the OpenCode gateway through
 * the normal ModelGateway selection, not by autowiring the class directly.
 *
 * <p>The real key is only seeded into the encrypted credential store and never
 * printed; the transaction rolls back afterwards so the key is not left in the
 * development database.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@EnabledIfEnvironmentVariable(named = "SPEC_AGENT_OPENCODE_KEY", matches = ".+")
class OpenCodeZenLiveSmokeTest {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private OpenCodeSettingsService settingsService;
    @Autowired
    private OpenCodeModelCatalog catalog;
    @Autowired
    private OpenCodeZenModelGateway gateway;
    @Autowired
    private StructuredModelOutputParser parser;
    @Autowired
    private StructuredOutputMapper mapper;

    private final ObjectMapper jsonMapper = new ObjectMapper();

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
    void liveSmokeSeedsCredentialDiscoversFreeModelsAndCompletes() throws Exception {
        System.out.println("=== OpenCodeZenLiveSmokeTest: explicit live smoke (public OpenCode allowed) ===");
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
        System.out.println("/models request: PASS; free models discovered: " + freeModels.size());

        // The explicitly selected model must be among the current free models.
        String selected = System.getenv().getOrDefault("SPEC_AGENT_OPENCODE_MODEL", "mimo-v2.5-free");
        assertThat(freeModels).contains(selected);
        System.out.println("selected free model: " + selected);

        // Run one real DRAFT_NODE completion through the gateway; the action
        // must come from the model's own envelope output and the structured
        // parser must accept the output.
        ModelRequest request = new ModelRequest(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                AgentTaskType.DRAFT_NODE, initialEnvelope(), Map.of());
        ModelResponse response = gateway.run(request);
        assertThat(response.requestAgentRunId()).isEqualTo(request.agentRunId());
        assertThat(response.requestContextSnapshotId()).isEqualTo(request.contextSnapshotId());
        assertThat(response.taskType()).isEqualTo(AgentTaskType.DRAFT_NODE);
        assertThat(response.action()).isEqualTo(AgentAction.ASK_NEXT_QUESTION);
        assertThat(response.outputJson()).isNotBlank();
        assertThat(response.trace()).containsEntry("promptVersion", "draft-node.v2");
        assertThat(response.trace()).containsKey("promptHash");
        assertThat(response.trace()).containsKey("modelOutputHash");

        NodeDraft draft = mapper.toNodeDraft(
                parser.parse(AgentTaskType.DRAFT_NODE, response.outputJson()));
        assertThat(draft.question()).isNotBlank();
        System.out.println("chat completion: PASS; model: " + selected
                + "; task: " + response.taskType().code()
                + "; action: " + response.action().code()
                + "; promptVersion: " + response.trace().get("promptVersion")
                + "; output length: " + response.outputJson().length());
    }

    @Test
    void liveSmokeAllFourPromptContractsParseStrictly() throws Exception {
        String apiKey = System.getenv("SPEC_AGENT_OPENCODE_KEY");
        seedSettings(apiKey);
        String snapshotId = UUID.randomUUID().toString();
        String routeId = UUID.randomUUID().toString();
        String nodeId = UUID.randomUUID().toString();
        String answerId = UUID.randomUUID().toString();
        String patchId = UUID.randomUUID().toString();

        String contextJson = """
                {"snapshotId":"%s","projectId":"%s","routeId":"%s","tipNodeId":"%s",
                 "operationType":"normal","contextHash":"live-smoke",
                 "lineage":[{"node":{"id":"%s","question":"What is the primary goal?","purpose":"clarify the goal",
                   "options":[{"id":"%s","label":"track progress","impact":"progress tracking"}],"allowFreeAnswer":true},
                   "answer":{"id":"%s","nodeId":"%s","selectedOptionId":null,"freeText":"We need a system that tracks progress."},
                   "patches":[{"id":"%s","claims":[{"kind":"goal","text":"The user needs progress tracking.","status":"confirmed","confidence":0.9,"sourceNodeId":"%s","sourceAnswerId":"%s"}]}]}],
                 "requirementState":{"claims":[{"kind":"goal","text":"The user needs progress tracking.","status":"confirmed","confidence":0.9,"sourceNodeId":"%s","sourceAnswerId":"%s"}]},
                 "specialInputs":{},
                 "allowedSourceRefs":["node:%s","answer:%s","patch:%s","context:%s","route:%s"]}
                """.formatted(snapshotId, UUID.randomUUID(), routeId, nodeId,
                nodeId, UUID.randomUUID(), answerId, nodeId, patchId, nodeId, answerId,
                nodeId, answerId, nodeId, answerId, nodeId, answerId, patchId, snapshotId, routeId);

        Map<AgentTaskType, AgentAction> expectations = Map.of(
                AgentTaskType.DRAFT_NODE, AgentAction.ASK_NEXT_QUESTION,
                AgentTaskType.INTERPRET_ANSWER, AgentAction.INTERPRET_ANSWER,
                AgentTaskType.DRAFT_ANSWER_PATCH, AgentAction.INTERPRET_ANSWER,
                AgentTaskType.DRAFT_SPEC, AgentAction.GENERATE_SPEC);

        for (Map.Entry<AgentTaskType, AgentAction> entry : expectations.entrySet()) {
            AgentTaskType taskType = entry.getKey();
            AgentAction expectedAction = entry.getValue();
            String taskInput = taskInputFor(taskType, answerId, nodeId);
            String inputJson = "{\"context\":" + contextJson + ",\"taskInput\":" + taskInput + "}";

            ModelRequest request = new ModelRequest(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    UUID.fromString(snapshotId), taskType, inputJson, Map.of());
            ModelResponse response = gateway.run(request);

            assertThat(response.action()).as("action for %s", taskType.code())
                    .isEqualTo(expectedAction);
            JsonNode parsed = parser.parse(taskType, response.outputJson());
            assertThat(response.trace()).containsKey("promptVersion");
            System.out.println("task " + taskType.code() + ": PASS; action: "
                    + response.action().code() + "; promptVersion: "
                    + response.trace().get("promptVersion") + "; output length: "
                    + response.outputJson().length());

            switch (taskType) {
                case DRAFT_NODE -> {
                    NodeDraft draft = mapper.toNodeDraft(parsed);
                    assertThat(draft.question()).isNotBlank();
                }
                case INTERPRET_ANSWER -> {
                    AnswerInterpretationResult result = mapper.toInterpretation(parsed);
                    assertThat(result).isNotNull();
                }
                case DRAFT_ANSWER_PATCH -> {
                    AnswerPatchDraft draft = mapper.toPatchDraft(parsed);
                    // Runtime identity: the model may not choose ids.
                    assertThat(draft.claims()).allMatch(claim -> claim.id() != null);
                    assertThat(draft.claims()).allMatch(claim -> claim.sourceNodeId() == null);
                }
                case DRAFT_SPEC -> {
                    SpecDraft draft = mapper.toSpecDraft(parsed);
                    // Every section must be grounded and every source reference
                    // must have been copied from the allowed set.
                    assertThat(new SpecGroundingGate().validate(draft).accepted())
                            .as("DRAFT_SPEC must pass the grounding gate")
                            .isTrue();
                    List<String> allowed = List.of("node:" + nodeId, "answer:" + answerId,
                            "patch:" + patchId, "context:" + snapshotId, "route:" + routeId);
                    draft.sourceRefsBySection().values().forEach(refs -> {
                        assertThat(refs).allSatisfy(ref -> assertThat(ref)
                                .as("source reference must be copied from allowedSourceRefs")
                                .isIn(allowed));
                    });
                }
                default -> throw new IllegalStateException("unexpected task: " + taskType);
            }
        }
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

    private String initialEnvelope() {
        String snapshotId = UUID.randomUUID().toString();
        return "{\"context\":{\"snapshotId\":\"" + snapshotId
                + "\",\"projectId\":\"" + UUID.randomUUID()
                + "\",\"routeId\":\"" + UUID.randomUUID()
                + "\",\"tipNodeId\":null,\"operationType\":\"normal\",\"contextHash\":\"live\","
                + "\"lineage\":[],\"requirementState\":{\"claims\":[]},\"specialInputs\":{},"
                + "\"allowedSourceRefs\":[\"context:" + snapshotId + "\"]},"
                + "\"taskInput\":{\"mode\":\"initial\"}}";
    }

    private String taskInputFor(AgentTaskType taskType, String answerId, String nodeId) {
        return switch (taskType) {
            case DRAFT_NODE -> "{\"mode\":\"initial\"}";
            case INTERPRET_ANSWER -> "{\"answer\":{\"id\":\"" + answerId + "\",\"nodeId\":\""
                    + nodeId + "\",\"selectedOptionId\":null,\"freeText\":\"We need progress tracking.\"}}";
            case DRAFT_ANSWER_PATCH -> "{\"answer\":{\"id\":\"" + answerId + "\",\"nodeId\":\""
                    + nodeId + "\",\"selectedOptionId\":null,\"freeText\":\"We need progress tracking.\"},"
                    + "\"interpretation\":{\"confirmedTexts\":[\"progress tracking\"],\"assumedTexts\":[],"
                    + "\"unresolvedTexts\":[],\"conflictTexts\":[]}}";
            case DRAFT_SPEC -> "{}";
            default -> throw new IllegalArgumentException("no task input for " + taskType);
        };
    }
}
