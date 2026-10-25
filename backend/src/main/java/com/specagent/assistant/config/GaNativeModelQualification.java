package com.specagent.assistant.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件名:GaNativeModelQualification.java
 *
 * 用途:GA 原生工具调用的<b>逐模型</b>资格登记。这是刻意显式的配置事实,不是推断:
 * "提供商已接入"、"Chat Completions 可用"、"模型支持原生 Tool Calling"、"GA 实际执行
 * 已验证"是四件不同的事,只有经过真实资格验证的模型才允许进入 GA 执行路径。
 *
 * <p>OpenCode Zen 沿用既有规则(可信元数据确认 Chat Completions 即可执行);
 * Kilo Gateway 则只承认显式登记在 {@code spec.global-assistant.ga-native.kilo-qualified-models}
 * 中的模型 id —— 绝不按 {@code :free} 后缀或"是个 Chat 模型"推断工具调用能力。
 * 未登记的模型返回 {@code GA_MODEL_PROTOCOL_UNSUPPORTED},不再用
 * {@code GA_MODEL_NOT_CONFIGURED} 掩盖"尚未取得资格"。
 *
 * <p>资格证据(模型 id、时间、失败类型)记录在 {@code docs/MODEL_GATEWAY.md}。
 */
@Component
@ConfigurationProperties(prefix = "spec.global-assistant.ga-native")
public class GaNativeModelQualification implements com.specagent.model.contract.CustomNativeToolQualification {

    /** 已通过原生工具调用资格验证的 Kilo 模型 id;空表示尚未登记任何 Kilo 模型。 */
    private List<String> kiloQualifiedModels = new ArrayList<>();
    public record CustomTarget(String baseUrl,String model) {}
    private List<CustomTarget> customQualifiedTargets=new ArrayList<>();
    public List<CustomTarget> getCustomQualifiedTargets() {return List.copyOf(customQualifiedTargets);}
    public boolean hasCustomTargets() {return !customQualifiedTargets.isEmpty();}
    public void setCustomQualifiedTargets(List<CustomTarget> targets) {
        customQualifiedTargets=targets==null?new ArrayList<>():new ArrayList<>(targets);
    }
    /** Explicit endpoint AND model qualification; never infer support from a model name alone. */
    public boolean customQualified(String baseUrl,String model) {
        return baseUrl!=null && model!=null && customQualifiedTargets.stream()
            .anyMatch(t->baseUrl.equals(t.baseUrl()) && model.equals(t.model()));
    }

    public List<String> getKiloQualifiedModels() {
        return List.copyOf(kiloQualifiedModels);
    }

    public void setKiloQualifiedModels(List<String> models) {
        this.kiloQualifiedModels = models == null ? new ArrayList<>() : new ArrayList<>(models);
    }

    /** Kilo 模型是否具备 GA 原生工具调用资格(精确匹配,不做任何后缀推断)。 */
    public boolean kiloQualified(String model) {
        return model != null && !model.isBlank() && kiloQualifiedModels.contains(model);
    }
}
