package com.specagent.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 文件名:P3AClaimGrounding.java
 *
 * 用途:P3-A B 层"CONFIRMED claim 是否真有依据"的**确定性**校验(评测侧)。
 *
 * 背景(上次代码审查确认的问题):live 套件从数据库读 claim 时只取
 * {@code kind/status/text},丢弃了来源字段,而 {@code ungroundedConfirmed}
 * 仅检查文本是否为空——于是它恒为零,既不能证明 grounding,也不能发现
 * 真实缺陷。
 *
 * 依据真实数据结构({@code Claim} / {@code AnswerPatch}):
 * <ul>
 *   <li>{@code Claim} 自身携带 {@code sourceNodeId} / {@code sourceAnswerId};
 *       生产运行时对 CONFIRMED claim 二者都设为该不可变 Answer 的节点/回答
 *       ({@code AnswerCycleService.groundClaims});</li>
 *   <li>{@code answer_patches} 行另持权威来源 {@code source_node_id} /
 *       {@code source_answer_id},其 {@code project_id} / {@code route_id}
 *       定义了这条 patch 的归属。</li>
 * </ul>
 *
 * 校验规则(不修改生产 schema,只读取既有事实):
 * <ol>
 *   <li>有效来源优先取 claim 自身引用;claim 未携带时回退到承载它的 patch
 *       的权威来源——两者用不同字段表达,不强制统一;</li>
 *   <li>claim 自身引用若存在,必须与 patch 的权威来源**锚定一致**,不得指向
 *       别的 Answer/Node;</li>
 *   <li>Answer 与 Node 必须真实存在,且都属于该 patch 的 project;</li>
 *   <li>Answer 的 route 必须等于 patch 的 route;</li>
 *   <li>Answer 的 node 必须等于有效来源节点(自身一致);</li>
 *   <li>该 route 必须仍是 open——superseded/archived/deleted 视为已失效来源。</li>
 * </ol>
 * 任一条件不满足即判为**不 grounded**;缺失或无效来源绝不算作 grounded。
 */
public final class P3AClaimGrounding {

    private P3AClaimGrounding() {
    }

    /** 一条已持久化 claim 的评测视图(只包含 grounding 判定所需字段)。 */
    public record ClaimView(String kind, String status, String text,
                            UUID sourceNodeId, UUID sourceAnswerId) {

        public boolean confirmed() {
            return "CONFIRMED".equals(status);
        }
    }

    /** 承载 claim 的 answer patch 的权威来源身份。 */
    public record PatchSource(UUID projectId, UUID routeId,
                              UUID sourceNodeId, UUID sourceAnswerId) {
    }

    public record AnswerRow(UUID id, UUID projectId, UUID routeId, UUID nodeId) {
    }

    public record NodeRow(UUID id, UUID projectId) {
    }

    /** route 行;{@code lifecycleStatus} 取 {@code open|superseded|archived|deleted}。 */
    public record RouteRow(UUID id, UUID projectId, String lifecycleStatus) {
    }

    /** 只读来源目录:把观察到的 id 解析为真实存储行(缺失返回 {@code null})。 */
    public interface SourceCatalog {
        AnswerRow findAnswer(UUID id);

        NodeRow findNode(UUID id);

        RouteRow findRoute(UUID id);
    }

    public static final String OPEN = "open";

    /** 判定单条 claim 是否 grounded。 */
    public static boolean isGrounded(PatchSource patch, ClaimView claim,
                                     SourceCatalog catalog) {
        if (patch == null || claim == null || catalog == null) {
            return false;
        }
        // 有效来源:claim 自身引用优先,缺失则回退到 patch 的权威来源。
        UUID effectiveAnswerId = claim.sourceAnswerId() != null
                ? claim.sourceAnswerId() : patch.sourceAnswerId();
        UUID effectiveNodeId = claim.sourceNodeId() != null
                ? claim.sourceNodeId() : patch.sourceNodeId();
        if (effectiveAnswerId == null || effectiveNodeId == null) {
            return false; // 缺失来源
        }
        // claim 自身引用存在时必须锚定到 patch 的权威来源,不得指向别处。
        if (claim.sourceAnswerId() != null
                && !claim.sourceAnswerId().equals(patch.sourceAnswerId())) {
            return false;
        }
        if (claim.sourceNodeId() != null
                && !claim.sourceNodeId().equals(patch.sourceNodeId())) {
            return false;
        }

        AnswerRow answer = catalog.findAnswer(effectiveAnswerId);
        if (answer == null) {
            return false; // 引用不存在的 Answer
        }
        NodeRow node = catalog.findNode(effectiveNodeId);
        if (node == null) {
            return false; // 引用不存在的 Node
        }
        if (!Objects.equals(answer.projectId(), patch.projectId())
                || !Objects.equals(node.projectId(), patch.projectId())) {
            return false; // 错项目来源
        }
        if (!Objects.equals(answer.routeId(), patch.routeId())) {
            return false; // 错路线来源
        }
        if (!Objects.equals(answer.nodeId(), effectiveNodeId)) {
            return false; // Answer 与来源节点不一致
        }
        RouteRow route = catalog.findRoute(patch.routeId());
        if (route == null || !OPEN.equals(route.lifecycleStatus())) {
            return false; // 已失效来源(route 不再 open)
        }
        return true;
    }

    /** 返回其中所有**不 grounded** 的 CONFIRMED claim。 */
    public static List<ClaimView> ungroundedConfirmed(PatchSource patch, List<ClaimView> claims,
                                                      SourceCatalog catalog) {
        List<ClaimView> result = new ArrayList<>();
        for (ClaimView claim : claims) {
            if (claim != null && claim.confirmed() && !isGrounded(patch, claim, catalog)) {
                result.add(claim);
            }
        }
        return result;
    }
}
