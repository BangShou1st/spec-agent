package com.specagent.agent;

import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.common.Json;
import com.specagent.workspace.context.ContextBuilder;
import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeOption;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteLifecycleStatus;
import com.specagent.workspace.route.RouteService;
import com.specagent.workspace.route.RegenerateResult;
import com.specagent.workspace.spec.RequirementStateQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
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
 * 文件名:ScriptedRouteIsolationIntegrationTest.java
 *
 * 测试目标:在模型可见的 envelope 层面验证路由隔离,经由异步 AgentRun 表面驱动。
 * spy 委托给真实确定性引擎并捕获每个 {@link AgentRequestEnvelope}:fork 或归档之后,
 * 活动路由的 envelope 必须排除兄弟哨兵、被取代的 id,以及冻结上下文之外的任何记录。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ScriptedRouteIsolationIntegrationTest {

    private static final String SIBLING_SENTINEL = "SCRIPTED_SIBLING_SENTINEL_7c1f";
    private static final String OLD_ANSWER_SENTINEL = "OLD_ANSWER_SENTINEL_2e8a";
    private static final String REGEN_INSTRUCTION = "Regenerate: make it sharper";

    @Autowired
    private ProjectService projectService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private DecisionCycleTestDriver draftDriver;
    @Autowired
    private AnswerCycleTestDriver answerDriver;
    @Autowired
    private Json json;
    @Autowired
    private ContextBuilder contextBuilder;

    @SpyBean
    private AgentDecisionEngine decisionEngine;

    private final List<AgentRequestEnvelope> captured = new ArrayList<>();

    @BeforeEach
    void captureModelRequests() {
        captured.clear();
        doAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return invocation.callRealMethod();
        }).when(decisionEngine).runStateUpdate(any(AgentRequestEnvelope.class));
        doAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return invocation.callRealMethod();
        }).when(decisionEngine).runDecision(any(AgentRequestEnvelope.class));
    }

    /** 用于排除性断言的 envelope 扁平化投影。 */
    private String envelopeText(AgentRequestEnvelope envelope) {
        StringBuilder combined = new StringBuilder();
        combined.append("route:").append(envelope.snapshot().routeId()).append('\n');
        if (envelope.event() != null && envelope.event().freeText() != null) {
            combined.append(envelope.event().freeText()).append('\n');
        }
        for (var entry : envelope.snapshot().lineage()) {
            if (entry.node() != null) {
                combined.append("node:").append(entry.node().id()).append('\n');
                var body = entry.node().body();
                if (body != null && body.text() != null) {
                    combined.append(body.text()).append('\n');
                }
            }
            if (entry.answer() != null) {
                combined.append("answer:").append(entry.answer().id()).append('\n');
                if (entry.answer().freeText() != null) {
                    combined.append(entry.answer().freeText()).append('\n');
                }
            }
            if (entry.patches() != null) {
                for (var patch : entry.patches()) {
                    combined.append("patch:").append(patch.id()).append('\n');
                }
            }
        }
        return combined.toString();
    }

    @Test
    void forkActiveRouteEnvelopesExcludeSiblingSentinelAndSupersededIds() {
        Project project = projectService.createProject("Fork isolation");

        // 路由 R1:root -> A -> A2。A 上的答案携带哨兵,
        // 它绝不能出现在 fork 路由的模型输入里。
        var draftRun = draftDriver.draftQuestion(project.id());
        Node root = nodeService.getNode(draftRun.producedNodeId()).orElseThrow();
        var rootRun = answerDriver.submitFreeText(project.id(), "First answer on R1 root");
        Node a = nodeService.getNode(rootRun.producedNodeId()).orElseThrow();
        var siblingRun = answerDriver.submitFreeText(project.id(),
                SIBLING_SENTINEL + " the sibling branch answer");
        Node a2 = nodeService.getNode(siblingRun.producedNodeId()).orElseThrow();
        UUID r1RouteId = rootRun.run().routeId();

        // 从根节点 fork:新的活动路由 R2 的上下文只包含共享根血统;
        // R1 的节点、答案与 patch 均被排除。
        Route fork = routeService.forkFromNode(project.id(), r1RouteId, root.id(), "Fork at root");
        UUID r2RouteId = fork.id();

        int forkPoint = captured.size();
        // 共享根在 R1 上已被回答,且只持有一个不可变的 Answer 身份,
        // 因此 R2 不能再次回答它。第一个分支动作在 R2 上起草下一个
        // Question(父节点 = 共享根),随后用户回答这个新 Question。
        var forkDraft = draftDriver.draftQuestion(project.id());
        Node b = nodeService.getNode(forkDraft.producedNodeId()).orElseThrow();
        assertThat(b.parentNodeId()).isEqualTo(root.id());
        var forkRun = answerDriver.submitFreeText(project.id(),
                "Fork branch answer that stays local to R2");

        // envelope 级隔离:fork 的 envelope 可以看到冻结的 R1 根前缀,
        // 但绝不能看到仅属于兄弟分支的节点或哨兵。
        List<AgentRequestEnvelope> forkEnvelopes =
                captured.subList(forkPoint, captured.size());
        assertThat(forkEnvelopes).isNotEmpty();

        for (AgentRequestEnvelope envelope : forkEnvelopes) {
            assertThat(envelope.snapshot().routeId())
                    .as("fork envelopes target the active fork route")
                    .isEqualTo(r2RouteId);
            String text = envelopeText(envelope);
            assertThat(text)
                    .as("fork envelope must exclude sibling content")
                    .doesNotContain(SIBLING_SENTINEL)
                    .doesNotContain("node:" + a.id())
                    .doesNotContain("node:" + a2.id());
            assertThat(envelope.snapshot().lineage())
                    .allSatisfy(entry -> {
                        if (entry.node() != null) {
                            assertThat(entry.node().id())
                                    .isNotEqualTo(a.id())
                                    .isNotEqualTo(a2.id());
                        }
                        if (entry.answer() != null) {
                            assertThat(entry.answer().freeText())
                                    .doesNotContain(SIBLING_SENTINEL);
                        }
                    });
        }

        // 共享根的答案保持在 fork 血统之中。
        assertThat(forkEnvelopes).anySatisfy(envelope -> {
            boolean found = envelope.snapshot().lineage().stream()
                    .anyMatch(entry -> entry.answer() != null
                            && rootRun.answerId().equals(entry.answer().id()));
            assertThat(found).as("shared root answer stays in fork lineage").isTrue();
        });
    }

    @Test
    void regenerateProjectionExcludesOldAnswerPatchAndChildSubtree() {
        Project project = projectService.createProject("Regenerate isolation");

        // 路由:root 已回答,随后 A 已回答(其答案与 patch 携带哨兵),
        // A 的回答还产出了 A 的子节点。
        var draftRun = draftDriver.draftQuestion(project.id());
        Node root = nodeService.getNode(draftRun.producedNodeId()).orElseThrow();
        answerDriver.submitFreeText(project.id(), "Root answer stays");
        var targetRun = answerDriver.submitFreeText(project.id(),
                OLD_ANSWER_SENTINEL + " the replaced answer");
        // 被回答的节点就是 run 记录的输入节点(提交时的 tip)。
        Node target = nodeService.getNode(targetRun.run().inputNodeId()).orElseThrow();
        Node child = nodeService.getNode(targetRun.producedNodeId()).orElseThrow();

        RegenerateResult committed = routeService.commitReplacementFromNode(
                project.id(), targetRun.run().routeId(), target.id(), child.id(), null,
                "A sharper replacement question", "A sharper purpose",
                List.of(NodeOption.of("Option label", "Option impact")), true);
        ContextSnapshot context = contextBuilder.buildForRegenerate(
                project.id(), targetRun.run().routeId(), target.id(),
                committed.replacementRoute().id(), committed.replacementNode().id(),
                REGEN_INSTRUCTION);
        RegenerateResult regen = new RegenerateResult(
                committed.oldRoute(), committed.replacementRoute(),
                committed.replacementNode());

        // 冻结的再生成上下文只携带共享父血统、旧问题文本和用户指令。
        assertThat(context.includedNodeIds())
                .contains(root.id())
                .doesNotContain(target.id())
                .doesNotContain(child.id());
        assertThat(context.includedAnswerIds())
                .doesNotContain(targetRun.answerId());
        assertThat(context.includedPatchIds())
                .doesNotContain(targetRun.patchId());

        // 再生成后的路由是打开、活动的,并指向一个全新节点。
        Route replacement = routeService.getRoute(
                projectService.getProject(project.id()).orElseThrow().activeRouteId()).orElseThrow();
        assertThat(replacement.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
    }

    @Test
    void archivedSiblingRouteStaysExcludedFromActiveEnvelopes() {
        Project project = projectService.createProject("Archived route exclusion");

        var draftRun = draftDriver.draftQuestion(project.id());
        Node root = nodeService.getNode(draftRun.producedNodeId()).orElseThrow();
        var r1Run = answerDriver.submitFreeText(project.id(), "R1 archived content");
        Node a = nodeService.getNode(r1Run.producedNodeId()).orElseThrow();
        UUID r1RouteId = r1Run.run().routeId();

        Route fork = routeService.forkFromNode(project.id(), r1RouteId, root.id(), "Fork at root");
        routeService.archiveRoute(project.id(), r1RouteId);

        int forkPoint = captured.size();
        // 共享根不能在 fork 路由上被再次回答(单一不可变 Answer 身份);
        // 先在活动的 fork 路由上起草下一个 Question,再回答它。
        var forkDraft = draftDriver.draftQuestion(project.id());
        Node b = nodeService.getNode(forkDraft.producedNodeId()).orElseThrow();
        assertThat(b.parentNodeId()).isEqualTo(root.id());
        var activeRun = answerDriver.submitFreeText(project.id(),
                "Active branch keeps working after archiving sibling");

        for (AgentRequestEnvelope envelope : captured.subList(forkPoint, captured.size())) {
            assertThat(envelope.snapshot().routeId()).isEqualTo(fork.id());
            assertThat(envelope.snapshot().lineage())
                    .allSatisfy(entry -> {
                        if (entry.node() != null) {
                            assertThat(entry.node().id()).isNotEqualTo(a.id());
                        }
                    });
        }
    }
}
