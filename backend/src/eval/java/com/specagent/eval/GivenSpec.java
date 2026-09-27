package com.specagent.eval;

import java.util.List;

/**
 * 文件名:GivenSpec.java
 *
 * 用途:场景定义中 {@code given} 块的结构化声明:初始图状态步骤
 * ({@link GraphStep})、触发用的用户事件、路由/焦点上下文、场景可用的能力
 * 与资源,以及脚本化的 B-fast Brain 输出。{@code canonical()} 生成场景哈希
 * 用的规范化字符串。
 *
 * 协作:由 {@link ScenarioDefinition} 持有,运行器据此冻结输入快照。
 */
public record GivenSpec(
        String projectTitleSeed,
        List<GraphStep> initialGraph,
        UserEvent userEvent,
        RouteContextSpec routeContext,
        FocusContextSpec focusContext,
        List<CapabilitySpec> capabilities,
        List<ResourceSpec> resources,
        BrainScript brainScript) {

    public GivenSpec {
        initialGraph = initialGraph == null ? List.of() : List.copyOf(initialGraph);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        resources = resources == null ? List.of() : List.copyOf(resources);
    }

    public String canonical() {
        return "given[title(" + projectTitleSeed + ");"
                + GraphStep.canonical(initialGraph) + ";"
                + UserEvent.canonical(userEvent) + ";"
                + routeContext.canonical() + ";"
                + focusContext.canonical() + ";"
                + CapabilitySpec.canonical(capabilities) + ";"
                + ResourceSpec.canonical(resources) + ";"
                + brainScript.canonical() + "]";
    }
}
