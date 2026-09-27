package com.specagent.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文件名:LiveSmokeEnvironment.java
 *
 * 测试专用:live smoke 环境就绪性检查。读取进程环境变量,判断一次显式的
 * OpenCode live smoke 是否允许运行,并打印安全诊断信息(仅网关选择器、所选模型、
 * 掩码后的 key 后缀)。完整 API key 绝不打印或返回;
 * {@link #maskSuffix} 只暴露最后四个字符。
 *
 * 检查逻辑是对传入 env map 的纯函数,便于单元测试而不触碰进程环境。
 * {@link #check()} 委托 {@link #check(Map)},传入 {@link System#getenv()}。
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
     * 返回密钥的最后四个字符;密钥缺失或太短、无法留下有意义后缀时返回 {@code "?"}。
     * 绝不返回完整密钥或超出后缀范围的内容。
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
