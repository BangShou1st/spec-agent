package com.specagent.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Test-only live smoke environment readiness.
 *
 * <p>Reads the process environment and decides whether an explicit OpenCode
 * live smoke may run, printing safe diagnostics (gateway selector, selected
 * model, masked key suffix only). The full API key is never printed or
 * returned; {@link #maskSuffix} only exposes the last four characters.
 *
 * <p>The checker is a pure function over a supplied env map so it can be unit
 * tested without touching the process environment. {@link #check()} delegates
 * to {@link #check(Map)} with {@link System#getenv()}.
 */
public final class LiveSmokeEnvironment {

    public static final String GATEWAY_ENV = "SPEC_AGENT_MODEL_GATEWAY";
    public static final String KEY_ENV = "SPEC_AGENT_OPENCODE_KEY";
    public static final String MODEL_ENV = "SPEC_AGENT_OPENCODE_MODEL";
    public static final String DEFAULT_MODEL = "mimo-v2.5-free";

    private LiveSmokeEnvironment() {
    }

    public static Readiness check() {
        return check(System.getenv());
    }

    static Readiness check(Map<String, String> env) {
        List<String> blockers = new ArrayList<>();

        String key = env.getOrDefault(KEY_ENV, "");
        if (key.isBlank()) {
            blockers.add("missing " + KEY_ENV);
        }

        String gateway = env.getOrDefault(GATEWAY_ENV, "");
        if (!"opencode".equals(gateway)) {
            blockers.add(GATEWAY_ENV + " must be opencode (found: "
                    + (gateway.isBlank() ? "unset" : gateway) + ")");
        }

        String model = env.getOrDefault(MODEL_ENV, DEFAULT_MODEL);
        if (model.isBlank() || !model.endsWith("-free")) {
            blockers.add("selected model must end with -free (found: "
                    + (model.isBlank() ? "unset" : model) + ")");
        }

        return new Readiness(blockers.isEmpty(), blockers, maskSuffix(key), gateway, model);
    }

    /**
     * Last four characters of the secret, or {@code "?"} when the secret is
     * missing or too short to leave a meaningful suffix. Never returns the full
     * secret or more than the suffix.
     */
    public static String maskSuffix(String secret) {
        if (secret == null || secret.isBlank() || secret.length() <= 4) {
            return "?";
        }
        return secret.substring(secret.length() - 4);
    }

    public record Readiness(boolean ready,
                            List<String> blockers,
                            String maskedSuffix,
                            String gateway,
                            String selectedModel) {

        public void print() {
            System.out.println("=== live smoke environment ===");
            System.out.println("gateway selector: " + (gateway.isBlank() ? "unset" : gateway));
            System.out.println("selected model: " + selectedModel);
            System.out.println("key masked: \u2022\u2022\u2022\u2022" + maskedSuffix);
            blockers.forEach(blocker -> System.out.println("BLOCKED: " + blocker));
        }
    }
}
