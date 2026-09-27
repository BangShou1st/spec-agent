package com.specagent.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件名:VariantSpec.java
 *
 * 用途:场景的一个防过拟合参数化变体。场景从不绑定固定 UUID、固定自然
 * 语言句子、固定顺序或固定路由名。变体表达扰动轴(通过 {@code paraphraseIndex}
 * 改写措辞、打乱无关上下文、焦点与活动路由分离、诱饵能力与资源、路由成员
 * 变化),而场景语义保持不变。{@code seed} 保证每个变体可复现。
 *
 * usage 标签支撑校准/保留(holdout)纪律:CALIBRATION 变体可以参与
 * prompt 调优,HOLDOUT 变体绝不参与。
 *
 * 协作:由 {@link ScenarioDefinition} 持有,传给 {@link ScenarioRunner}
 * 驱动具体执行。
 */
public record VariantSpec(
        String variantId,
        long seed,
        int paraphraseIndex,
        boolean shuffleIrrelevantContext,
        boolean focusDiffersFromActive,
        List<String> decoyCapabilities,
        List<String> decoyResourceTexts,
        String routeVariation,
        Usage usage) {

    public enum Usage {
        GENERAL,
        CALIBRATION,
        HOLDOUT
    }

    public VariantSpec {
        decoyCapabilities = decoyCapabilities == null ? List.of() : List.copyOf(decoyCapabilities);
        decoyResourceTexts = decoyResourceTexts == null ? List.of() : List.copyOf(decoyResourceTexts);
    }

    public static VariantSpec base(String variantId, long seed) {
        return new VariantSpec(variantId, seed, 0, false, false,
                List.of(), List.of(), null, Usage.GENERAL);
    }

    public static Builder builder(String variantId, long seed) {
        return new Builder(variantId, seed);
    }

    public String canonicalString() {
        List<String> decoys = new ArrayList<>(decoyCapabilities);
        decoys.sort(String::compareTo);
        List<String> resources = new ArrayList<>(decoyResourceTexts);
        resources.sort(String::compareTo);
        return "variant(" + variantId + "," + seed + "," + paraphraseIndex + ","
                + shuffleIrrelevantContext + "," + focusDiffersFromActive + ","
                + decoys + "," + resources + "," + routeVariation + "," + usage + ")";
    }

    public static final class Builder {
        private final String variantId;
        private final long seed;
        private int paraphraseIndex;
        private boolean shuffleIrrelevantContext;
        private boolean focusDiffersFromActive;
        private final List<String> decoyCapabilities = new ArrayList<>();
        private final List<String> decoyResourceTexts = new ArrayList<>();
        private String routeVariation;
        private Usage usage = Usage.GENERAL;

        private Builder(String variantId, long seed) {
            this.variantId = variantId;
            this.seed = seed;
        }

        public Builder paraphraseIndex(int paraphraseIndex) {
            this.paraphraseIndex = paraphraseIndex;
            return this;
        }

        public Builder shuffleIrrelevantContext(boolean shuffle) {
            this.shuffleIrrelevantContext = shuffle;
            return this;
        }

        public Builder focusDiffersFromActive(boolean differs) {
            this.focusDiffersFromActive = differs;
            return this;
        }

        public Builder decoyCapability(String capabilityId) {
            this.decoyCapabilities.add(capabilityId);
            return this;
        }

        public Builder decoyResourceText(String text) {
            this.decoyResourceTexts.add(text);
            return this;
        }

        public Builder routeVariation(String routeVariation) {
            this.routeVariation = routeVariation;
            return this;
        }

        public Builder usage(Usage usage) {
            this.usage = usage;
            return this;
        }

        public VariantSpec build() {
            return new VariantSpec(variantId, seed, paraphraseIndex,
                    shuffleIrrelevantContext, focusDiffersFromActive,
                    List.copyOf(decoyCapabilities), List.copyOf(decoyResourceTexts),
                    routeVariation, usage);
        }
    }
}
