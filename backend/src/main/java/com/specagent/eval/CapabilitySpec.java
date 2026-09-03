package com.specagent.eval;

import java.util.List;
import java.util.Map;

/** A capability the runner registers for the scenario (test adapter). */
public record CapabilitySpec(
        String capabilityId,
        String sideEffectClass,
        boolean succeed,
        Map<String, Object> resultContent) {

    public CapabilitySpec {
        resultContent = resultContent == null ? Map.of() : Map.copyOf(resultContent);
    }

    public static String canonical(List<CapabilitySpec> capabilities) {
        return "caps" + capabilities.stream()
                .map(spec -> "cap(" + spec.capabilityId() + ","
                        + spec.sideEffectClass() + "," + spec.succeed() + ")")
                .sorted()
                .toList();
    }
}
