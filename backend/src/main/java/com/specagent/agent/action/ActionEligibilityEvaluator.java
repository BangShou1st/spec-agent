package com.specagent.agent.action;

import com.specagent.agent.protocol.ActionEligibilityReasonCode;

import com.specagent.agent.action.ActionEligibilityEvaluator;
import com.specagent.agent.protocol.ActionEligibility;
import com.specagent.agent.protocol.ActionEligibilityConstraint;

import com.specagent.agent.protocol.ActionFamily;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.protocol.ClaimView;
import com.specagent.agent.protocol.LineageEntry;
import com.specagent.common.Hashes;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 文件名:ActionEligibilityEvaluator.java
 *
 * 用途:Runtime 持有的纯函数评估器,只判定确定性的必要条件与硬性禁令,
 * 刻意不做语义排序。
 *
 * 冻结原则:约束执行,而不是约束推理。未解决的冲突和未回答的问题
 * 绝不会在这里限制可执行的动作集合——它们仍会如实呈现在
 * {@code observation.conflicts} 中,但在这个边界上 ACTION 的选择不受限。
 * 执行安全属于能力可见性、WAIT 依赖、重复、schema、refs、stale、policy、
 * 权限和图不变量等门禁,绝不属于"语义冲突/未决问题"的映射。
 *
 * 协作:由 ActionEligibilityGate 调用,产出 ActionEligibility
 * (各动作族的资格约束与依据哈希)。
 */
public final class ActionEligibilityEvaluator {

    public ActionEligibility evaluate(AgentRequestEnvelope request) {
        EnumMap<ActionFamily, ActionEligibilityConstraint> evaluated =
                new EnumMap<>(ActionFamily.class);
        for (ActionFamily family : ActionFamily.values()) {
            evaluated.put(family, ActionEligibilityConstraint.allow());
        }

        // agent-input.v2 中不存在待处理依赖的表示,因此 WAIT 无法
        // 从这份输入获得确定性的资格,直接拒绝。
        evaluated.put(ActionFamily.WAIT, ActionEligibilityConstraint.deny(
                ActionEligibilityReasonCode.NO_PENDING_DEPENDENCY));

        if (request.snapshot().availableCapabilities().isEmpty()) {
            evaluated.put(ActionFamily.INVOKE_CAPABILITY, ActionEligibilityConstraint.deny(
                    ActionEligibilityReasonCode.CAPABILITY_NOT_VISIBLE));
        }

        Map<String, ActionEligibilityConstraint> constraints = new LinkedHashMap<>();
        List<String> eligibleFamilies = new ArrayList<>();
        for (ActionFamily family : ActionFamily.values()) {
            ActionEligibilityConstraint constraint = evaluated.get(family);
            constraints.put(family.code(), constraint);
            if (constraint.eligible()) {
                eligibleFamilies.add(family.code());
            }
        }

        return new ActionEligibility(
                ActionEligibility.VERSION,
                eligibleFamilies,
                constraints,
                basisHash(request, evaluated));
    }

    /**
     * 稳定的语义身份:Runtime 的 UUID 与集合顺序被刻意排除在外,
     * 而本评估器用到的每个事实都以规范化排序后的形式纳入哈希。
     */
    private String basisHash(AgentRequestEnvelope request,
                             EnumMap<ActionFamily, ActionEligibilityConstraint> evaluated) {
        List<String> facts = new ArrayList<>();
        facts.add("event:" + request.event().kind());
        facts.add("hasEventText:" + hasText(request.event().freeText()));
        facts.add("persistenceIntent:" + String.valueOf(request.event().persistenceIntent()));
        for (ClaimView claim : request.snapshot().effectiveClaims()) {
            facts.add("claim:" + claim.kind() + ":" + claim.status() + ":"
                    + normalizeText(claim.text()));
        }
        for (LineageEntry entry : request.snapshot().lineage()) {
            facts.add("node:" + entry.node().kind() + ":"
                    + normalizeText(entry.node().body().text()) + ":answered="
                    + (entry.answer() != null));
            if (entry.answer() != null) {
                facts.add("answer:" + normalizeText(entry.answer().freeText()));
            }
        }
        request.snapshot().availableCapabilities().forEach(capability ->
                facts.add("capability:" + capability.id() + ":" + capability.version()
                        + ":" + capability.sideEffectClass()));
        evaluated.forEach((family, constraint) -> facts.add(
                "constraint:" + family.code() + ":" + constraint.eligible() + ":"
                        + constraint.reasonCodes().stream().map(Enum::name).sorted().toList()));
        facts.sort(String::compareTo);
        return Hashes.sha256Hex(String.join("\n", facts));
    }

    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    static String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .strip()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
