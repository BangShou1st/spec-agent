package com.specagent.agent;

import com.specagent.agent.runtime.AgentRunStatus;

import com.specagent.agent.FakeAnswerRepairIntegrationTest;

import com.specagent.agent.AnswerCycleTestDriver;
import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerRepository;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatch;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.patch.Claim;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:FakeAnswerRepairIntegrationTest.java
 *
 * 测试目标:通过异步 ANSWER_CYCLE 验证答案的修复/续跑集成行为:失败的答题 run
 * 仍会持久化不可变答案,RESUME_ANSWER 重试从既有答案续跑,而不会产生第二个定稿答案。
 * 语义重放保证(续跑 envelope 从持久化的 Answer 重建)由
 * {@code AnswerResumeSemanticReplayIntegrationTest} 覆盖;本套件覆盖持久化产物
 * 不变量与所有权校验。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FakeAnswerRepairIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private AnswerRepository answerRepository;
    @Autowired
    private AnswerPatchService answerPatchService;
    @Autowired
    private AnswerCycleTestDriver answerDriver;

    @Test
    void resumeCompletesCycleWithSingleAnswerAndSinglePatch() {
        Project project = projectService.createProject("Repair project");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "What is the most important outcome?", null, List.of(), true);

        // 阶段 1:模拟一个持久化了 Answer 但未完成即失败的循环——
        // 被回答的节点仍是活动末梢。
        Answer persisted = answerService.finalizeAnswer(
                project.id(), project.activeRouteId(), root.id(), null, "clarified", "user");
        UUID originalNodeId = root.id();
        UUID answerId = persisted.id();

        // 阶段 2:从持久化的答案续跑(修复路径)。patch 检查点被复用,
        // 不允许出现第二个 Answer。
        var repaired = answerDriver.resumeAnswer(project.id(), answerId);

        assertThat(repaired.run().status()).isEqualTo(AgentRunStatus.COMPLETED);

        // 没有创建第二个答案。
        assertThat(answerRepository.findByRouteAndNodeIds(project.activeRouteId(), List.of(originalNodeId)))
                .hasSize(1);

        // patch 检查点保持唯一:该答案恰好对应一个 patch。
        List<AnswerPatch> patches = answerPatchService.findByRoute(project.activeRouteId());
        assertThat(patches).hasSize(1);
        AnswerPatch patch = patches.get(0);
        assertThat(patch.sourceNodeId()).isEqualTo(originalNodeId);
        assertThat(patch.sourceAnswerId()).isEqualTo(answerId);
        Claim confirmed = patch.claims().stream().filter(Claim::isConfirmed).findFirst().orElseThrow();
        assertThat(confirmed.sourceNodeId()).isEqualTo(originalNodeId);
        assertThat(confirmed.sourceAnswerId()).isEqualTo(answerId);

        // 修复 run 已完成并记录了所有产出的 id。
        assertThat(repaired.run().producedAnswerId()).isEqualTo(answerId);
        assertThat(repaired.run().producedPatchId()).isEqualTo(patch.id());
        Route finalRoute = routeService.getRoute(project.activeRouteId()).orElseThrow();
        assertThat(finalRoute.tipNodeId()).isEqualTo(repaired.run().producedNodeId());
    }

    @Test
    void resumeRejectsAnswerFromForeignProjectOrInactiveFlow() {
        Project projectA = projectService.createProject("Repair foreign project");
        Node node = nodeService.createRootNode(projectA.id(), projectA.activeRouteId(),
                "What is the goal?", null, List.of(), true);
        Answer answer = answerService.finalizeAnswer(
                projectA.id(), projectA.activeRouteId(), node.id(), null, "clarified", "user");

        // 跨项目续跑:驱动以项目 B 为目标、却使用项目 A 的答案。
        Project projectB = projectService.createProject("Repair foreign project B");

        UUID answerId = answer.id();
        try {
            answerDriver.resumeAnswer(projectB.id(), answerId);
            org.junit.jupiter.api.Assertions.fail("foreign-project resume must fail");
        } catch (RuntimeException expected) {
            // fail closed(期望失败)
        }

        // 非活动流程续跑:fork 走,使答案所在路由不再是活动路由。
        Route forkRoute = routeService.forkFromNode(
                projectA.id(), projectA.activeRouteId(), node.id(), "sibling route");
        assertThat(projectService.getProject(projectA.id()).orElseThrow().activeRouteId())
                .isEqualTo(forkRoute.id());

        try {
            answerDriver.resumeAnswer(projectA.id(), answerId);
            org.junit.jupiter.api.Assertions.fail("inactive-flow resume must fail");
        } catch (RuntimeException expected) {
            // fail closed(期望失败)
        }

        // 不可变答案从未被复制或修改。
        assertThat(answerRepository.findByRouteAndNodeIds(
                projectA.activeRouteId(), List.of(node.id()))).hasSize(1);
    }
}
