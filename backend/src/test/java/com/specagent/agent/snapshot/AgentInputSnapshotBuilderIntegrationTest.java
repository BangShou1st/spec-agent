package com.specagent.agent.snapshot;

import com.specagent.agent.contract.AgentContracts;
import com.specagent.agent.contract.AgentEvent;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.DecisionBudget;
import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.answer.Answer;
import com.specagent.answer.AnswerService;
import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextOperationType;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The input snapshot projection over a real frozen ContextSnapshot: the
 * durable manifest stays authoritative, the projection carries generic Graph
 * language, runtime-owned ids, and the project title only as low-authority
 * metadata.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AgentInputSnapshotBuilderIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private AnswerPatchService answerPatchService;
    @Autowired
    private ContextBuilder contextBuilder;
    @Autowired
    private AgentInputSnapshotBuilder snapshotBuilder;

    @Test
    void projectsLineageAnswersAndPatchesFromTheFrozenManifest() {
        Project project = projectService.createProject("快照投影项目");
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId,
                "最重要的目标是什么？", null, List.of(), true);
        Node child = nodeService.createChildNode(project.id(), routeId, root.id(),
                "当前如何收集需求？", null, List.of(), true);
        Answer rootAnswer = answerService.finalizeAnswer(
                project.id(), routeId, root.id(), null, "减少邮件沟通", "user");
        Claim claim = Claim.of(ClaimKind.GOAL, "用户希望减少邮件沟通",
                ClaimStatus.CONFIRMED, root.id(), rootAnswer.id());
        AnswerPatch patch = answerPatchService.save(
                project.id(), routeId, root.id(), rootAnswer.id(), List.of(claim), null);

        ContextSnapshot snapshot = contextBuilder.buildFromActiveRoute(
                project.id(), UUID.randomUUID(), ContextOperationType.NORMAL);
        AgentRequestEnvelope envelope = snapshotBuilder.buildEnvelope(
                UUID.randomUUID(), snapshot,
                new AgentEvent("CONTINUE", child.id(), null, "继续"),
                new DecisionBudget(2));

        assertThat(envelope.protocolVersion()).isEqualTo("agent-input.v2");
        assertThat(envelope.snapshot().snapshotId()).isEqualTo(snapshot.id().toString());
        assertThat(envelope.snapshot().contextHash()).isEqualTo(snapshot.contextHash());
        assertThat(envelope.snapshot().anchorNodeId()).isEqualTo(child.id());
        assertThat(envelope.snapshot().lineage()).hasSize(2);
        assertThat(envelope.snapshot().lineage().get(0).node().body().text())
                .isEqualTo("最重要的目标是什么？");
        assertThat(envelope.snapshot().lineage().get(0).answer().freeText())
                .isEqualTo("减少邮件沟通");
        assertThat(envelope.snapshot().lineage().get(0).patches().get(0).id()).isEqualTo(patch.id());
        assertThat(envelope.snapshot().effectiveClaims()).hasSize(1);
        // Project title is low-authority metadata only.
        assertThat(envelope.snapshot().metadata().projectTitle()).isEqualTo("快照投影项目");
        // Allowed source refs cover exactly the manifest plus context/route.
        assertThat(envelope.snapshot().allowedSourceRefs())
                .contains("node:" + root.id(), "answer:" + rootAnswer.id(),
                        "patch:" + patch.id(), "route:" + routeId,
                        "context:" + snapshot.id());
        assertThat(envelope.snapshot().autonomy().mode()).isEqualTo("ADVISOR");
        assertThat(envelope.decisionBudget().maxModelCalls()).isEqualTo(2);
    }

    @Test
    void wireSerializationUsesGenericGraphLanguageOnly() {
        Project project = projectService.createProject("通用语言检查");
        UUID routeId = project.activeRouteId();
        nodeService.createRootNode(project.id(), routeId, "问题文本", null, List.of(), true);

        ContextSnapshot snapshot = contextBuilder.buildFromActiveRoute(
                project.id(), UUID.randomUUID(), ContextOperationType.NORMAL);
        AgentRequestEnvelope envelope = snapshotBuilder.buildEnvelope(
                UUID.randomUUID(), snapshot,
                new AgentEvent("INITIAL", snapshot.tipNodeId(), null, null),
                new DecisionBudget(2));

        String wire = AgentContracts.write(envelope);
        assertThat(wire)
                .doesNotContain("\"question\"")
                .doesNotContain("DRAFT_NODE")
                .doesNotContain("INTERPRET_ANSWER")
                .contains("\"body\"")
                .contains("\"acceptsFreeText\"");
        // Round-trips through the strict mapper (unknown fields would fail).
        assertThat(AgentContracts.read(wire, AgentRequestEnvelope.class).runId())
                .isEqualTo(envelope.runId());
    }
}
