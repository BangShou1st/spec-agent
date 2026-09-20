package com.specagent.spec;

import com.specagent.answer.Answer;
import com.specagent.answer.AnswerService;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.patch.AnswerPatch;
import com.specagent.patch.AnswerPatchService;
import com.specagent.patch.Claim;
import com.specagent.patch.ClaimKind;
import com.specagent.patch.ClaimStatus;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic Markdown rendering of one SpecSnapshot. The exporter is a
 * pure view: it never mutates state and never calls the model. The snapshot
 * stays the structured source of truth in the database; every export is
 * regenerated on demand so a rendering bug can never corrupt a derived
 * artifact.
 *
 * <p>Two deliberately different views over the same route:
 * <ul>
 *   <li>{@link Variant#SNAPSHOT} — faithful export of the snapshot itself for
 *     audit: metadata table, the frozen sections, unresolved items, and the
 *     resolved source-tracing appendix.</li>
 *   <li>{@link Variant#DELIVERY} — the development handoff document: the
 *     frozen sections PLUS the live requirement state (confirmed / assumed /
 *     unresolved claims replayed from the route lineage) and a Q&amp;A digest,
 *     so developers get the requirement state, not only the frozen prose.</li>
 * </ul>
 */
@Service
public class SpecMarkdownExporter {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private static final int REF_TEXT_LIMIT = 80;
    private static final int LINEAGE_WALK_LIMIT = 100;

    private final NodeService nodeService;
    private final AnswerService answerService;
    private final AnswerPatchService answerPatchService;

    public SpecMarkdownExporter(NodeService nodeService,
                                AnswerService answerService,
                                AnswerPatchService answerPatchService) {
        this.nodeService = nodeService;
        this.answerService = answerService;
        this.answerPatchService = answerPatchService;
    }

    public enum Variant {
        SNAPSHOT, DELIVERY;

        public static Variant fromCode(String code) {
            if ("snapshot".equalsIgnoreCase(code)) {
                return SNAPSHOT;
            }
            if ("delivery".equalsIgnoreCase(code)) {
                return DELIVERY;
            }
            throw new IllegalArgumentException("Unknown spec export variant: " + code);
        }

        public String code() {
            return name().toLowerCase();
        }
    }

    public String export(SpecSnapshot snapshot, String projectTitle, Variant variant) {
        return variant == Variant.SNAPSHOT
                ? renderSnapshot(snapshot, projectTitle)
                : renderDelivery(snapshot, projectTitle);
    }

    /**
     * 快照导出：忠实呈现派生快照本身，面向审计与回溯。
     */
    private String renderSnapshot(SpecSnapshot snapshot, String projectTitle) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(safeTitle(projectTitle)).append(" · 需求规格快照\n\n");
        md.append("> 本文件是派生产物，不是权威来源。事实源是节点谱系、回答与补丁（Graph）。\n\n");
        md.append("| 快照 ID | 路线 ID | 谱系端点 | 上下文快照 | 生成时间 |\n");
        md.append("| --- | --- | --- | --- | --- |\n");
        md.append("| ").append(snapshot.id()).append(" | ").append(snapshot.routeId())
                .append(" | ").append(snapshot.tipNodeId())
                .append(" | ").append(snapshot.contextSnapshotId() == null
                        ? "—" : snapshot.contextSnapshotId())
                .append(" | ").append(TIME_FORMAT.format(snapshot.createdAt())).append(" |\n\n");

        appendSections(md, snapshot);
        appendUnresolved(md, snapshot, Map.of());
        appendProvenance(md, snapshot);
        return md.toString();
    }

    /**
     * 开发交付导出：快照正文 + 路线需求状态（已确认/假定/未决 claims）+
     * 问答记录。这是给开发团队的工作文档，信息量大于快照的忠实导出。
     */
    private String renderDelivery(SpecSnapshot snapshot, String projectTitle) {
        DeliveryContext ctx = buildDeliveryContext(snapshot);
        StringBuilder md = new StringBuilder();
        md.append("# ").append(safeTitle(projectTitle)).append(" · 开发需求文档\n\n");
        md.append("> 本文档由需求规格快照 ").append(shortId(snapshot.id()))
                .append("（").append(TIME_FORMAT.format(snapshot.createdAt())).append(" 生成）整理而来。")
                .append("「未决问题」与「假定」中的事项在开发前需要先确认。\n\n");

        appendSections(md, snapshot);

        appendConfirmedClaims(md, ctx.claimsByStatus().get(ClaimStatus.CONFIRMED));
        appendAssumedClaims(md, ctx.claimsByStatus().get(ClaimStatus.ASSUMED));
        appendUnresolved(md, snapshot, ctx.claimsByStatus());
        appendQaDigest(md, ctx.qaEntries());
        appendProvenance(md, snapshot);
        return md.toString();
    }

    /**
     * 交付文档的需求状态来源：从快照的谱系端点沿 parentNodeId 走到根，
     * 收集每个节点的回答与对应 patch claims。只读，失败容忍（单节点缺失
     * 不阻断导出）。
     */
    private DeliveryContext buildDeliveryContext(SpecSnapshot snapshot) {
        List<Node> lineage = walkLineage(snapshot.tipNodeId());
        List<UUID> nodeIds = lineage.stream().map(Node::id).toList();
        Map<UUID, Answer> answerByNodeId = new LinkedHashMap<>();
        for (Answer answer : answerService.findAnswersForRouteAndNodeIds(
                snapshot.routeId(), nodeIds)) {
            answerByNodeId.put(answer.nodeId(), answer);
        }
        List<AnswerPatch> patches = answerByNodeId.isEmpty()
                ? List.of()
                : answerPatchService.findBySourceAnswerIds(
                        List.copyOf(answerByNodeId.values()).stream()
                                .map(Answer::id).toList());

        Map<ClaimStatus, List<Claim>> claimsByStatus = new LinkedHashMap<>();
        for (ClaimStatus status : List.of(ClaimStatus.CONFIRMED, ClaimStatus.ASSUMED,
                ClaimStatus.UNRESOLVED)) {
            claimsByStatus.put(status, new ArrayList<>());
        }
        for (AnswerPatch patch : patches) {
            for (Claim claim : patch.claims()) {
                List<Claim> bucket = claimsByStatus.get(claim.status());
                if (bucket != null) {
                    bucket.add(claim);
                }
            }
        }

        List<QaEntry> qaEntries = new ArrayList<>();
        for (int i = lineage.size() - 1; i >= 0; i--) {
            Node node = lineage.get(i);
            qaEntries.add(new QaEntry(node, answerByNodeId.get(node.id())));
        }
        return new DeliveryContext(claimsByStatus, qaEntries);
    }

    /** 从谱系端点沿 parentNodeId 向上走到根；环路保护 + 深度上限。 */
    private List<Node> walkLineage(UUID tipNodeId) {
        List<Node> lineage = new ArrayList<>();
        Set<UUID> visited = new LinkedHashSet<>();
        UUID cursor = tipNodeId;
        while (cursor != null && visited.size() < LINEAGE_WALK_LIMIT
                && visited.add(cursor)) {
            Node node = nodeService.getNode(cursor).orElse(null);
            if (node == null) {
                break;
            }
            lineage.add(node);
            cursor = node.parentNodeId();
        }
        return lineage;
    }

    private void appendConfirmedClaims(StringBuilder md, List<Claim> confirmed) {
        if (confirmed == null || confirmed.isEmpty()) {
            return;
        }
        md.append("## 已确认的需求要点\n\n");
        for (Claim claim : confirmed) {
            md.append("- ").append(claimLine(claim)).append("\n");
        }
        md.append("\n");
    }

    private void appendAssumedClaims(StringBuilder md, List<Claim> assumed) {
        if (assumed == null || assumed.isEmpty()) {
            return;
        }
        md.append("## 假定（尚未确认）\n\n");
        for (Claim claim : assumed) {
            md.append("- ").append(claimLine(claim)).append("\n");
        }
        md.append("\n");
    }

    private String claimLine(Claim claim) {
        String label = kindLabel(claim.kind());
        return label == null ? oneLine(claim.text()) : label + "：" + oneLine(claim.text());
    }

    /** 领域中立的 kind 中文标签；未识别的 kind 返回 null（只输出文本）。 */
    private String kindLabel(ClaimKind kind) {
        return switch (kind) {
            case GOAL -> "目标";
            case SCOPE -> "范围";
            case CONSTRAINT -> "约束";
            case SUCCESS_CRITERION -> "验收标准";
            case OUTPUT_EXPECTATION -> "期望产出";
            case STAKEHOLDER -> "干系人";
            case RISK -> "风险";
            case ASSUMPTION -> "假定";
            case OPEN_QUESTION -> "未决问题";
            case CONFLICT -> "冲突";
            case OTHER -> null;
        };
    }

    private void appendSections(StringBuilder md, SpecSnapshot snapshot) {
        if (snapshot.sections().isEmpty()) {
            md.append("## 规格内容\n\n（本快照没有生成任何章节。）\n\n");
            return;
        }
        for (SpecSection section : snapshot.sections()) {
            md.append("## ").append(oneLine(section.title())).append("\n\n");
            String content = section.content() == null || section.content().isBlank()
                    ? "（本节没有内容。）" : section.content().trim();
            md.append(content).append("\n\n");
        }
    }

    /** 未决问题：快照的 unresolvedItems 与路线上的未决 claims 合并去重。 */
    private void appendUnresolved(StringBuilder md, SpecSnapshot snapshot,
                                  Map<ClaimStatus, List<Claim>> claimsByStatus) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> lines = new ArrayList<>();
        for (UnresolvedItem item : snapshot.unresolvedItems()) {
            if (seen.add(oneLine(item.text()).toLowerCase())) {
                String text = oneLine(item.text());
                if (item.category() != null && !item.category().isBlank()
                        && !"unresolved".equals(item.category())) {
                    text = text + "（" + item.category() + "）";
                }
                lines.add(text);
            }
        }
        for (Claim claim : claimsByStatus.getOrDefault(ClaimStatus.UNRESOLVED, List.of())) {
            if (seen.add(oneLine(claim.text()).toLowerCase())) {
                lines.add(oneLine(claim.text()));
            }
        }
        if (lines.isEmpty()) {
            return;
        }
        md.append("## 未决问题\n\n");
        for (String line : lines) {
            md.append("- ").append(line).append("\n");
        }
        md.append("\n");
    }

    /** 谱系问答摘要（根 → 端点），给开发者看每个结论是哪句问答产生的。 */
    private void appendQaDigest(StringBuilder md, List<QaEntry> qaEntries) {
        if (qaEntries.isEmpty()) {
            return;
        }
        md.append("## 问答记录\n\n");
        for (QaEntry entry : qaEntries) {
            md.append("- 问：").append(oneLine(entry.node().question())).append("\n");
            md.append("  答：").append(answerDigest(entry.answer())).append("\n");
        }
        md.append("\n");
    }

    private String answerDigest(Answer answer) {
        if (answer == null) {
            return "（未回答）";
        }
        String freeText = answer.freeText();
        if (freeText != null && !freeText.isBlank()) {
            return truncate(oneLine(freeText));
        }
        String optionLabel = resolveOptionLabel(answer);
        return optionLabel == null ? "（已选择选项）" : truncate(optionLabel);
    }

    private void appendProvenance(StringBuilder md, SpecSnapshot snapshot) {
        md.append("## 来源与追溯\n\n");
        if (snapshot.sourceRefs().isEmpty()) {
            md.append("本快照没有携带来源引用。\n");
            return;
        }
        for (SourceReference ref : snapshot.sourceRefs()) {
            md.append("- ").append(describeRef(ref)).append("\n");
        }
    }

    /**
     * 把 machine ref（node:<uuid> / answer:<uuid>）解析成人类可读的描述；
     * 无法解析时保留原始 id，绝不编造内容。
     */
    private String describeRef(SourceReference ref) {
        String kind = ref.kind().code();
        UUID id = ref.refId();
        if (ref.kind() == SourceKind.NODE) {
            return nodeService.getNode(id)
                    .map(node -> kind + ":" + id + "（" + describeNode(node) + "）")
                    .orElse(kind + ":" + id);
        }
        if (ref.kind() == SourceKind.ANSWER) {
            return answerService.getAnswer(id)
                    .map(answer -> kind + ":" + id + "（" + describeAnswer(answer) + "）")
                    .orElse(kind + ":" + id);
        }
        return kind + ":" + id;
    }

    private String describeNode(Node node) {
        String text = node.question() != null && !node.question().isBlank()
                ? node.question() : node.purpose();
        return text == null || text.isBlank() ? "节点" : truncate(oneLine(text));
    }

    private String describeAnswer(Answer answer) {
        String freeText = answer.freeText();
        if (freeText != null && !freeText.isBlank()) {
            return "回答：" + truncate(oneLine(freeText));
        }
        String optionLabel = resolveOptionLabel(answer);
        if (optionLabel != null) {
            return "选择：" + truncate(optionLabel);
        }
        return "回答";
    }

    private String resolveOptionLabel(Answer answer) {
        String optionId = answer.selectedOptionId();
        if (optionId == null) {
            return null;
        }
        return nodeService.getNode(answer.nodeId())
                .flatMap(node -> node.options().stream()
                        .filter(option -> optionId.equals(option.id().toString()))
                        .findFirst()
                        .map(option -> option.label()))
                .orElse(optionId);
    }

    private static String safeTitle(String projectTitle) {
        return projectTitle == null || projectTitle.isBlank() ? "未命名项目" : projectTitle.trim();
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s*\\n\\s*", " ").trim();
    }

    private static String truncate(String text) {
        return text.length() <= REF_TEXT_LIMIT
                ? text : text.substring(0, REF_TEXT_LIMIT) + "…";
    }

    private static String shortId(UUID id) {
        String raw = id.toString();
        return raw.substring(0, 8);
    }

    /** 交付文档的派生上下文：按状态分桶的 claims + 谱系问答（根 → 端点）。 */
    private record DeliveryContext(Map<ClaimStatus, List<Claim>> claimsByStatus,
                                   List<QaEntry> qaEntries) {
    }

    private record QaEntry(Node node, Answer answer) {
    }
}
