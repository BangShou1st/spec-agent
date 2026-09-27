package com.specagent.eval;

/**
 * 文件名:StateSummary.java
 *
 * 用途:一次快照边界(前/后置)的粗粒度规范状态计数:路由、节点、Answer、
 * patch、proposal 和能力调用次数,用于计算状态增量(state delta)。
 *
 * 协作:由 {@link ScenarioRunner} 在尝试前后各采集一次,构成
 * {@link AttemptContext} 与 {@link ObservationEnvelope} 的一部分。
 */
public record StateSummary(
        int routes,
        int nodes,
        int answers,
        int patches,
        int proposals,
        int capabilityInvocations) {

    public static StateSummary of(int routes, int nodes, int answers,
                                  int patches, int proposals, int capabilityInvocations) {
        return new StateSummary(routes, nodes, answers, patches, proposals, capabilityInvocations);
    }

    public static StateSummary empty() {
        return new StateSummary(0, 0, 0, 0, 0, 0);
    }
}
