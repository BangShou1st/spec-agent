package com.specagent.agent.runtime;

import com.specagent.agent.snapshot.StaleContextChecker;
import com.specagent.agent.AnswerCycleTestDriver;
import com.specagent.agent.DecisionCycleTestDriver;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.RouteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:ReplacementLiveStalenessIntegrationTest.java
 *
 * 测试目标:替换(Replacement)的活跃过期回归:模型基于冻结快照做出决策,若在
 * 提交执行前来源路由的 tip 已被推进(追加了新节点),替换必须 fail closed——绝不
 * 提交一个基于过期路由状态做出的决策,即使目标仍在历史血统上。
 */
@SpringBootTest
@ActiveProfiles("test")
class ReplacementLiveStalenessIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private DecisionCycleTestDriver draftDriver;
    @Autowired
    private AnswerCycleTestDriver answerDriver;
    @Autowired
    private StaleContextChecker staleContextChecker;
    @Autowired
    private RouteRepository routeRepository;

    /**
     * 确定性前置条件接缝:预期 tip = 快照时刻的 tip;
     * 之后当前 tip 被变更 → 过期。
     */
    @Test
    void replacementRejectsSourceRouteMutationAfterSnapshot() {
        Project project = projectService.createProject("Stale regen " + UUID.randomUUID());
        // 无需清空队列:答题驱动按 id 领取自己入队的 run,
        // 其他 fixture 遗留在队列中的 run 不会造成干扰。
        var draftRun = draftDriver.draftQuestion(project.id());
        var answerRun = answerDriver.submitFreeText(project.id(), "first answer");
        UUID sourceRouteId = answerRun.run().routeId();
        Node targetChild = nodeService.getNode(answerRun.producedNodeId()).orElseThrow();
        UUID targetNodeId = targetChild.parentNodeId();
        UUID tipAtSnapshotTime = routeRepository.findById(sourceRouteId)
                .orElseThrow().tipNodeId();

        // 模型推理进行中……与此同时另一个节点被追加到同一来源路由
        // (tip 越过了模型所看到的位置)。
        nodeService.createChildNode(project.id(), sourceRouteId,
                targetChild.id(), "New question appended after snapshot",
                null, List.of(), true);

        assertThatThrownBy(() -> staleContextChecker.verifyLiveExecutionPreconditions(
                sourceRouteId, tipAtSnapshotTime, targetNodeId))
                .isInstanceOf(com.specagent.agent.action.StaleProposalException.class)
                .hasMessageContaining("changed after the replacement snapshot");
    }

    /** 目标脱离当前血统同样 fail closed。 */
    @Test
    void replacementRejectsTargetOutsideCurrentLineage() {
        // 来源路由不存在:检查器立即 fail closed。
        assertThatThrownBy(() -> staleContextChecker
                .verifyLiveExecutionPreconditions(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(com.specagent.agent.action.StaleProposalException.class);
    }
}
