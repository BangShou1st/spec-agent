package com.specagent.eval;

import java.util.List;

/** A resource attached during setup, rendered from a text seed. */
public record ResourceSpec(String textSeed) {

    public static String canonical(List<ResourceSpec> resources) {
        return "resources" + resources.stream()
                .map(spec -> "res(" + spec.textSeed() + ")")
                .sorted()
                .toList();
    }
}
