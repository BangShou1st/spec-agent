package com.specagent.eval;

import com.specagent.capability.CapabilityAdapter;
import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityResult;
import com.specagent.capability.SideEffectClass;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 文件名:EvalProbeCapabilities.java
 *
 * 测试目标:评估用探针能力(测试夹具,非生产行为)。语料按 id 引用的
 * 固定通用探针:只读与本地持久化诱饵、外部诱饵,以及授权场景用的高风险
 * 本地/外部探针。每个探针记录自己的调用次数,成败由 {@link ScenarioRunner}
 * 安装的行为决定——Java 的 CapabilityRuntime、policy 引擎与确认流程始终是权威。
 */
public final class EvalProbeCapabilities {

    /** 按能力 id 记录的调用计数,每次尝试之间清空。 */
    public static final Map<String, AtomicInteger> INVOCATIONS = new ConcurrentHashMap<>();

    /** 按能力 id 记录的成功标志,由各场景安装。 */
    public static final Map<String, Boolean> SUCCEED = new ConcurrentHashMap<>();

    public static final String DECOY_READ_ONLY = "eval.decoy.read-only";
    public static final String DECOY_LOCAL_DURABLE = "eval.decoy.local-durable";
    public static final String DECOY_EXTERNAL = "eval.decoy.external";
    public static final String HIGH_RISK_LOCAL = "eval.high-risk.local-durable";
    public static final String HIGH_RISK_EXTERNAL = "eval.high-risk.external";

    private EvalProbeCapabilities() {
    }

    public static void reset() {
        INVOCATIONS.clear();
        SUCCEED.clear();
    }

    public static int invocationsOf(String capabilityId) {
        return INVOCATIONS.getOrDefault(capabilityId, new AtomicInteger()).get();
    }

    public static java.util.Map<String, Integer> invocationCounts() {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        INVOCATIONS.forEach((id, counter) -> {
            if (counter.get() > 0) {
                counts.put(id, counter.get());
            }
        });
        return counts;
    }

    private abstract static class ProbeAdapter implements CapabilityAdapter {
        private final String capabilityId;
        private final SideEffectClass sideEffectClass;
        private final boolean readOnly;

        private ProbeAdapter(String capabilityId, SideEffectClass sideEffectClass, boolean readOnly) {
            this.capabilityId = capabilityId;
            this.sideEffectClass = sideEffectClass;
            this.readOnly = readOnly;
        }

        @Override
        public CapabilityDescriptor descriptor() {
            return new CapabilityDescriptor(capabilityId, "1",
                    "eval probe " + capabilityId, Map.of(), Map.of(),
                    readOnly, sideEffectClass, List.of(), List.of());
        }

        @Override
        public CapabilityResult invoke(CapabilityInvocation invocation) {
            INVOCATIONS.computeIfAbsent(capabilityId, key -> new AtomicInteger()).incrementAndGet();
            boolean succeed = SUCCEED.getOrDefault(capabilityId, true);
            if (!succeed) {
                return CapabilityResult.failed(invocation.invocationId(),
                        invocation.invocationKey(), capabilityId, "eval scripted capability failure");
            }
            return new CapabilityResult(invocation.invocationId(),
                    invocation.invocationKey(), capabilityId,
                    CapabilityResult.Status.SUCCEEDED,
                    Map.of("marker", invocation.invocationKey()),
                    List.of(), Map.of(), List.of());
        }
    }

    /** 测试装配:在评估测试中注册固定的探针适配器。 */
    @TestConfiguration
    public static class Config {
        @Bean
        public CapabilityAdapter evalDecoyReadOnly() {
            return new ProbeAdapter(DECOY_READ_ONLY, SideEffectClass.NONE, true) {
            };
        }

        @Bean
        public CapabilityAdapter evalDecoyLocalDurable() {
            return new ProbeAdapter(DECOY_LOCAL_DURABLE, SideEffectClass.LOCAL_DURABLE, false) {
            };
        }

        @Bean
        public CapabilityAdapter evalDecoyExternal() {
            return new ProbeAdapter(DECOY_EXTERNAL, SideEffectClass.EXTERNAL_IRREVERSIBLE, false) {
            };
        }

        @Bean
        public CapabilityAdapter evalHighRiskLocal() {
            return new ProbeAdapter(HIGH_RISK_LOCAL, SideEffectClass.LOCAL_DURABLE, false) {
            };
        }

        @Bean
        public CapabilityAdapter evalHighRiskExternal() {
            return new ProbeAdapter(HIGH_RISK_EXTERNAL, SideEffectClass.EXTERNAL_IRREVERSIBLE, false) {
            };
        }
    }
}
