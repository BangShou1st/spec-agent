package com.specagent.eval;

import java.util.List;

/**
 * The {@code given} block of one scenario: initial graph steps, the
 * triggering user event, route/focus context, scenario capabilities and
 * resources, plus the scripted B-fast Brain output.
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
