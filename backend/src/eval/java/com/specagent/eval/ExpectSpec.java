package com.specagent.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 文件名:ExpectSpec.java
 *
 * 用途:场景定义中 {@code expect} 块的结构化声明:运行时不变量、必填属性
 * 校验({@link PropertyCheck})、可接受/禁止的主动作、期望/禁止的状态增量,
 * 以及调用预算({@link CallBudget})。是分层校验的期望基准。
 *
 * 协作:由 {@link ScenarioDefinition} 持有,校验器据此产出
 * {@link CheckResult} 与 {@link Violation}。
 */
public record ExpectSpec(
        Set<String> runtimeInvariants,
        List<PropertyCheck> requiredProperties,
        Set<String> acceptablePrimaryActions,
        Set<String> forbiddenActions,
        Map<String, Integer> expectedStateDeltas,
        Map<String, Integer> forbiddenStateDeltas,
        CallBudget callBudget) {

    public ExpectSpec {
        runtimeInvariants = runtimeInvariants == null ? Set.of() : Set.copyOf(runtimeInvariants);
        requiredProperties = requiredProperties == null ? List.of() : List.copyOf(requiredProperties);
        acceptablePrimaryActions =
                acceptablePrimaryActions == null ? Set.of() : Set.copyOf(acceptablePrimaryActions);
        forbiddenActions = forbiddenActions == null ? Set.of() : Set.copyOf(forbiddenActions);
        expectedStateDeltas = expectedStateDeltas == null ? Map.of() : Map.copyOf(expectedStateDeltas);
        forbiddenStateDeltas =
                forbiddenStateDeltas == null ? Map.of() : Map.copyOf(forbiddenStateDeltas);
    }

    public String canonical() {
        List<String> props = new ArrayList<>();
        for (PropertyCheck check : requiredProperties) {
            props.add(check.canonical());
        }
        props.sort(String::compareTo);
        return "expect[invariants(" + new TreeSet<>(runtimeInvariants) + ");"
                + "props(" + props + ");"
                + "accept(" + new TreeSet<>(acceptablePrimaryActions) + ");"
                + "forbid(" + new TreeSet<>(forbiddenActions) + ");"
                + "delta(" + new TreeMap<>(expectedStateDeltas) + ");"
                + "nodelta(" + new TreeMap<>(forbiddenStateDeltas) + ");"
                + "budget(" + callBudget.expectedStages() + ","
                + callBudget.minProductionCalls() + "," + callBudget.maxProductionCalls() + ","
                + callBudget.maxProviderRetries() + "," + callBudget.maxCapabilityCalls() + ")]";
    }
}
