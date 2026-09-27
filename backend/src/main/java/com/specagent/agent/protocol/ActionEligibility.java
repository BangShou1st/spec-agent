package com.specagent.agent.protocol;

import com.specagent.agent.protocol.ActionFamily;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件名:ActionEligibility.java
 *
 * 用途:Runtime 针对"单个决策事件 + 冻结快照"计算出的动作可用性掩码,
 * 告诉 Brain 哪些动作家族(ActionFamily)在当前上下文中允许被提出。
 *
 * 约束:紧凑构造器执行 fail-closed 校验——版本号必须等于 {@link #VERSION},
 * eligibleFamilies 不得重复且必须是合法家族码,constraints 必须覆盖全部家族
 * 并与 eligibleFamilies 一致,basisHash 必须是 64 位 SHA-256 十六进制摘要。
 */
public record ActionEligibility(
        String version,
        List<String> eligibleFamilies,
        Map<String, ActionEligibilityConstraint> constraints,
        String basisHash) {

    public static final String VERSION = "action-eligibility.v1";

    public ActionEligibility {
        eligibleFamilies = eligibleFamilies == null ? List.of() : List.copyOf(eligibleFamilies);
        constraints = constraints == null ? Map.of() : Map.copyOf(constraints);
        if (!VERSION.equals(version)) {
            throw new IllegalArgumentException("Unknown action eligibility version: " + version);
        }
        Set<String> declaredFamilies = new HashSet<>();
        for (String family : eligibleFamilies) {
            ActionFamily.fromCode(family);
            if (!declaredFamilies.add(family)) {
                throw new IllegalArgumentException("Duplicate eligible action family: " + family);
            }
        }
        Set<String> expected = java.util.Arrays.stream(ActionFamily.values())
                .map(ActionFamily::code)
                .collect(java.util.stream.Collectors.toSet());
        if (!constraints.keySet().equals(expected)) {
            throw new IllegalArgumentException(
                    "Eligibility constraints must cover the complete action family set");
        }
        for (String family : expected) {
            boolean listed = declaredFamilies.contains(family);
            if (constraints.get(family).eligible() != listed) {
                throw new IllegalArgumentException(
                        "Eligibility list/constraint mismatch for family: " + family);
            }
        }
        if (basisHash == null || !basisHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Eligibility basisHash must be a SHA-256 hex digest");
        }
    }
}
