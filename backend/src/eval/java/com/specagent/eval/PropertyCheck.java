package com.specagent.eval;

import java.util.Map;

/** One required-property check of the {@code expect} block. */
public record PropertyCheck(String property, Map<String, Object> args) {

    public PropertyCheck {
        args = args == null ? Map.of() : Map.copyOf(args);
    }

    public String canonical() {
        return "prop(" + property + "," + new java.util.TreeMap<>(args) + ")";
    }
}
