package com.specagent.spec;

import com.specagent.answer.Answer;
import com.specagent.answer.AnswerService;
import com.specagent.common.Ids;
import com.specagent.node.Node;
import com.specagent.node.NodeOption;
import com.specagent.node.NodeService;
import com.specagent.patch.AnswerPatch;
import com.specagent.patch.AnswerPatchService;
import com.specagent.patch.Claim;
import com.specagent.patch.ClaimKind;
import com.specagent.patch.ClaimStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

/**
 * Unit tests for the deterministic Markdown export of a SpecSnapshot. The
 * exporter is a pure view: same snapshot in, same Markdown out, no model, no
 * persistence. The delivery variant additionally projects the route's
 * requirement state (lineage claims + Q&A digest).
 */
@ExtendWith(MockitoExtension.class)
class SpecMarkdownExporterTest {

    @Mock
    private NodeService nodeService;

    @Mock
    private AnswerService answerService;

    @Mock
    private AnswerPatchService answerPatchService;

    private final UUID projectId = Ids.random();
    private final UUID routeId = Ids.random();
    private final UUID tipNodeId = Ids.random();
    private final UUID nodeId = Ids.random();
    private final UUID answerId = Ids.random();

    private SpecMarkdownExporter exporter() {
        return new SpecMarkdownExporter(nodeService, answerService, answerPatchService);
    }

    private SpecSnapshot snapshotWithSections() {
        return new SpecSnapshot(Ids.random(), projectId, routeId, tipNodeId, Ids.random(),
                "markdown",
                List.of(
                        SpecSection.of("背景与目标", "做一个 clarify 工具。\n- 目标一\n- 目标二"),
                        SpecSection.of("验收标准", "可回答所有问题")),
                List.of(UnresolvedItem.of("部署环境未确认", "unresolved")),
                List.of(SourceReference.of(SourceKind.NODE, nodeId),
                        SourceReference.of(SourceKind.ANSWER, answerId)),
                Ids.random(), Instant.parse("2026-09-19T10:15:00Z"));
    }

    @Test
    void snapshotVariantRendersFaithfulProvenanceView() {
        stubResolvedRefs();
        String md = exporter().export(snapshotWithSections(), "需求工作区", SpecMarkdownExporter.Variant.SNAPSHOT);

        assertThat(md).startsWith("# 需求工作区 · 需求规格快照");
        assertThat(md).contains("派生产物，不是权威来源");
        assertThat(md).contains("| 快照 ID | 路线 ID | 谱系端点 | 上下文快照 | 生成时间 |");
        assertThat(md).contains("## 背景与目标");
        assertThat(md).contains("- 目标一");
        assertThat(md).contains("## 未决问题");
        assertThat(md).contains("- 部署环境未确认");
        assertThat(md).contains("## 来源与追溯");
        assertThat(md).contains("node:" + nodeId + "（首个问题）");
        assertThat(md).contains("answer:" + answerId + "（回答：希望支持多路线）");
        // 忠实导出不携带需求状态与问答摘要。
        assertThat(md).doesNotContain("## 已确认的需求要点");
        assertThat(md).doesNotContain("## 问答记录");
    }

    @Test
    void deliveryVariantRendersDevelopmentHandoffDocument() {
        stubResolvedRefs();
        String md = exporter().export(snapshotWithSections(), "需求工作区", SpecMarkdownExporter.Variant.DELIVERY);

        assertThat(md).startsWith("# 需求工作区 · 开发需求文档");
        assertThat(md).contains("未决问题」与「假定」中的事项在开发前需要先确认");
        assertThat(md).contains("## 验收标准");
        // 溯源在交付文档中降级为附录，但仍然保留可追溯性。
        assertThat(md).contains("## 来源与追溯");
        assertThat(md).doesNotContain("| 快照 ID |");
    }

    @Test
    void deliveryVariantIncludesRequirementStateFromLineage() {
        stubResolvedRefs();
        stubLineageWithClaims();
        String md = exporter().export(snapshotWithSections(), "需求工作区", SpecMarkdownExporter.Variant.DELIVERY);

        assertThat(md).contains("## 已确认的需求要点");
        assertThat(md).contains("- 目标：把模糊想法整理成可执行的需求");
        assertThat(md).contains("## 假定（尚未确认）");
        assertThat(md).contains("- 假定：用户主要使用桌面浏览器");
        assertThat(md).contains("## 问答记录");
        assertThat(md).contains("- 问：您想要我帮助您完成什么任务？");
        assertThat(md).contains("答：需求澄清助手");
    }

    @Test
    void snapshotVariantDoesNotReadRequirementState() {
        // 快照导出是纯快照视图：即使路线有 claims 也不读取。
        stubResolvedRefs();
        stubLineageWithClaims();
        String md = exporter().export(snapshotWithSections(), "P", SpecMarkdownExporter.Variant.SNAPSHOT);
        assertThat(md).doesNotContain("## 已确认的需求要点");
        assertThat(md).doesNotContain("## 问答记录");
    }

    @Test
    void unresolvedRefsFallBackToRawIds() {
        lenient().when(nodeService.getNode(nodeId)).thenReturn(Optional.empty());
        lenient().when(answerService.getAnswer(answerId)).thenReturn(Optional.empty());
        String md = exporter().export(snapshotWithSections(), "P", SpecMarkdownExporter.Variant.SNAPSHOT);

        assertThat(md).contains("node:" + nodeId);
        assertThat(md).contains("answer:" + answerId);
    }

    @Test
    void optionAnswerIsDescribedByLabel() {
        UUID optionId = Ids.random();
        Node question = new Node(nodeId, projectId, null, null, null,
                "选择方向", null,
                List.of(new NodeOption(optionId, "走 A 路线", null, false)),
                true, Instant.now());
        Answer answer = new Answer(answerId, projectId, routeId, nodeId,
                optionId.toString(), null, "user", Instant.now());
        lenient().when(nodeService.getNode(nodeId)).thenReturn(Optional.of(question));
        lenient().when(answerService.getAnswer(answerId)).thenReturn(Optional.of(answer));

        String md = exporter().export(snapshotWithSections(), "P", SpecMarkdownExporter.Variant.SNAPSHOT);
        assertThat(md).contains("answer:" + answerId + "（选择：走 A 路线）");
    }

    @Test
    void blankTitleFallsBackToUnnamedProject() {
        String md = exporter().export(snapshotWithSections(), "  ", SpecMarkdownExporter.Variant.DELIVERY);
        assertThat(md).startsWith("# 未命名项目 · 开发需求文档");
    }

    @Test
    void unknownVariantCodeIsRejected() {
        assertThatThrownBy(() -> SpecMarkdownExporter.Variant.fromCode("pdf"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(SpecMarkdownExporter.Variant.fromCode("SNAPSHOT"))
                .isEqualTo(SpecMarkdownExporter.Variant.SNAPSHOT);
    }

    private void stubResolvedRefs() {
        Node question = new Node(nodeId, projectId, null, null, null,
                "首个问题", null, List.of(), true, Instant.now());
        Answer answer = new Answer(answerId, projectId, routeId, nodeId,
                null, "希望支持多路线", "user", Instant.now());
        lenient().when(nodeService.getNode(eq(nodeId))).thenReturn(Optional.of(question));
        lenient().when(answerService.getAnswer(eq(answerId))).thenReturn(Optional.of(answer));
    }

    /** 谱系：root(nodeId) ← tip(tipNodeId)，tip 有回答与已确认/假定 claims。 */
    private void stubLineageWithClaims() {
        Node root = new Node(nodeId, projectId, null, null, null,
                "您想要我帮助您完成什么任务？", null, List.of(), true, Instant.now());
        Node tip = new Node(tipNodeId, projectId, nodeId, null, null,
                "要解决什么问题？", null, List.of(), true, Instant.now());
        Answer tipAnswer = new Answer(answerId, projectId, routeId, tipNodeId,
                null, "需求澄清助手", "user", Instant.now());
        AnswerPatch patch = new AnswerPatch(Ids.random(), projectId, routeId, tipNodeId, answerId,
                List.of(
                        Claim.of(ClaimKind.GOAL, "把模糊想法整理成可执行的需求",
                                ClaimStatus.CONFIRMED, tipNodeId, answerId),
                        Claim.of(ClaimKind.ASSUMPTION, "用户主要使用桌面浏览器",
                                ClaimStatus.ASSUMED, null, null)),
                Ids.random(), Instant.now());
        lenient().when(nodeService.getNode(tipNodeId)).thenReturn(Optional.of(tip));
        lenient().when(nodeService.getNode(nodeId)).thenReturn(Optional.of(root));
        lenient().when(answerService.findAnswersForRouteAndNodeIds(eq(routeId), eq(List.of(tipNodeId, nodeId))))
                .thenReturn(List.of(tipAnswer));
        lenient().when(answerPatchService.findBySourceAnswerIds(eq(List.of(answerId))))
                .thenReturn(List.of(patch));
    }
}
