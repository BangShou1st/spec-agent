package com.specagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.contracts.AnswerInterpretationResult;
import com.specagent.agent.contracts.AnswerPatchDraft;
import com.specagent.agent.contracts.GapAnalysisResult;
import com.specagent.agent.contracts.NodeDraft;
import com.specagent.agent.contracts.SpecDraft;
import com.specagent.common.Json;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakeModelAdapterTest {

    private final FakeModelAdapter adapter = new FakeModelAdapter(new Json(new ObjectMapper()));

    private ModelRequest request(AgentTaskType taskType) {
        return new ModelRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                taskType,
                "{}",
                null);
    }

    @Test
    void fakeModelRequiresContextSnapshotId() {
        assertThatThrownBy(() -> new ModelRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                AgentTaskType.GAP_ANALYSIS,
                "{}",
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contextSnapshotId");
    }

    @Test
    void fakeGapAnalysisIsDeterministic() {
        ModelRequest firstRequest = request(AgentTaskType.GAP_ANALYSIS);
        ModelRequest secondRequest = request(AgentTaskType.GAP_ANALYSIS);

        ModelResponse first = adapter.run(firstRequest);
        ModelResponse second = adapter.run(secondRequest);

        assertThat(first.action()).isEqualTo(AgentAction.ASK_NEXT_QUESTION);
        assertThat(first.outputJson()).isEqualTo(second.outputJson());

        GapAnalysisResult result = new Json(new ObjectMapper())
                .read(first.outputJson(), GapAnalysisResult.class);
        assertThat(result.readyForSpec()).isFalse();
        assertThat(result.missingAspects()).containsExactly("initial clarification");
        assertThat(first.trace()).containsEntry("adapter", "fake");
    }

    @Test
    void fakeDraftNodeReturnsAskNextQuestion() {
        ModelResponse response = adapter.run(request(AgentTaskType.DRAFT_NODE));

        assertThat(response.action()).isEqualTo(AgentAction.ASK_NEXT_QUESTION);

        NodeDraft draft = new Json(new ObjectMapper())
                .read(response.outputJson(), NodeDraft.class);
        assertThat(draft.question()).isNotBlank();
        assertThat(draft.allowFreeAnswer()).isTrue();
    }

    @Test
    void fakeInterpretAnswerIsDeterministic() {
        ModelRequest request = request(AgentTaskType.INTERPRET_ANSWER);

        ModelResponse first = adapter.run(request);
        ModelResponse second = adapter.run(request);

        assertThat(first.action()).isEqualTo(AgentAction.INTERPRET_ANSWER);
        assertThat(first.outputJson()).isEqualTo(second.outputJson());

        AnswerInterpretationResult result = new Json(new ObjectMapper())
                .read(first.outputJson(), AnswerInterpretationResult.class);
        assertThat(result.confirmedTexts()).isNotEmpty();
        assertThat(first.trace()).containsEntry("adapter", "fake");
        assertThat(first.trace()).containsEntry("deterministic", "true");
        assertThat(first.trace()).containsEntry("task", "interpret_answer");
    }

    @Test
    void fakeDraftAnswerPatchIsDeterministic() {
        ModelRequest request = request(AgentTaskType.DRAFT_ANSWER_PATCH);

        ModelResponse first = adapter.run(request);
        ModelResponse second = adapter.run(request);

        assertThat(first.action()).isEqualTo(AgentAction.INTERPRET_ANSWER);
        assertThat(first.outputJson()).isEqualTo(second.outputJson());

        AnswerPatchDraft draft = new Json(new ObjectMapper())
                .read(first.outputJson(), AnswerPatchDraft.class);
        assertThat(draft.claims()).isNotEmpty();
        assertThat(first.trace()).containsEntry("task", "draft_answer_patch");
    }

    @Test
    void fakeDraftSpecIsDeterministic() {
        ModelRequest request = request(AgentTaskType.DRAFT_SPEC);

        ModelResponse first = adapter.run(request);
        ModelResponse second = adapter.run(request);

        assertThat(first.action()).isEqualTo(AgentAction.GENERATE_SPEC);
        assertThat(first.outputJson()).isEqualTo(second.outputJson());

        SpecDraft draft = new Json(new ObjectMapper())
                .read(first.outputJson(), SpecDraft.class);
        assertThat(draft.sections()).isNotEmpty();
        assertThat(draft.sourceRefsBySection()).isNotEmpty();
        assertThat(first.trace()).containsEntry("task", "draft_spec");
    }

    @Test
    void fakeResponseCarriesAgentRunIdAndContextSnapshotId() {
        UUID agentRunId = UUID.randomUUID();
        UUID contextSnapshotId = UUID.randomUUID();
        ModelRequest req = new ModelRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                agentRunId,
                contextSnapshotId,
                AgentTaskType.PLAN_NEXT_ACTION,
                "{}",
                null);

        ModelResponse response = adapter.run(req);

        assertThat(response.requestAgentRunId()).isEqualTo(agentRunId);
        assertThat(response.requestContextSnapshotId()).isEqualTo(contextSnapshotId);
        assertThat(response.taskType()).isEqualTo(AgentTaskType.PLAN_NEXT_ACTION);
    }

    @Test
    void fakeModelDoesNotRequireExternalProviderSdk() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.specagent.agent");

        ArchRule rule = noClasses()
                .that().resideInAPackage("com.specagent.agent..")
                .should().dependOnClassesThat()
                .haveNameMatching("org\\.springframework\\.ai..|com\\.openai..|dev\\.langchain4j..")
                .because("Fake model adapter and agent contracts must not require external provider SDKs");

        rule.check(classes);
    }
}