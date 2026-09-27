package com.specagent.eval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 文件名:ScenarioDefinition.java
 *
 * 用途:一个声明式评测场景及其全部变体:场景 id 稳定(E01…E25),
 * {@code given} 块({@link GivenSpec})定义初始状态与触发,{@code expect}
 * 块({@link ExpectSpec})定义验收基准。场景哈希覆盖影响评测的语义
 * (given + expect + 变体内容),与变体排序无关;每个变体单独记录校准/
 * 保留(holdout)用途,方便后续 prompt 调优排除保留集。
 *
 * 协作:是评测链路的输入根——由 {@link ScenarioRunner} 执行,
 * {@link EvalArtifactWriter} 记录其哈希,{@link CausalReportGenerator} 按其
 * 解释失败证据。
 */
public record ScenarioDefinition(
        String scenarioId,
        String scenarioVersion,
        String description,
        GivenSpec given,
        ExpectSpec expect,
        List<VariantSpec> variants) {

    public ScenarioDefinition {
        variants = variants == null ? List.of() : List.copyOf(variants);
    }

    /** 快速失败地校验结构规则(变体 id 唯一、动作不重叠)。 */
    public void validate() {
        if (variants.isEmpty()) {
            throw new IllegalArgumentException(
                    "Scenario " + scenarioId + " requires at least one variant");
        }
        List<String> ids = new ArrayList<>();
        for (VariantSpec variant : variants) {
            ids.add(variant.variantId());
        }
        if (ids.stream().distinct().count() != ids.size()) {
            throw new IllegalArgumentException(
                    "Scenario " + scenarioId + " has duplicate variant ids: " + ids);
        }
        for (String action : expect.acceptablePrimaryActions()) {
            if (expect.forbiddenActions().contains(action)) {
                throw new IllegalArgumentException(
                        "Scenario " + scenarioId + " lists action as both acceptable and forbidden: "
                                + action);
            }
        }
    }

    /** 场景语义(given + expect + 变体内容)的稳定哈希。 */
    public String scenarioHash() {
        List<String> renderedVariants = new ArrayList<>();
        for (VariantSpec variant : variants) {
            renderedVariants.add(variant.canonicalString());
        }
        renderedVariants.sort(Comparator.naturalOrder());
        String canonical = "scenario[" + scenarioId + "," + scenarioVersion + ";"
                + given.canonical() + ";" + expect.canonical() + ";"
                + renderedVariants + "]";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public List<String> calibrationVariants() {
        return variants.stream()
                .filter(variant -> variant.usage() == VariantSpec.Usage.CALIBRATION)
                .map(VariantSpec::variantId)
                .sorted()
                .toList();
    }

    public List<String> holdoutVariants() {
        return variants.stream()
                .filter(variant -> variant.usage() == VariantSpec.Usage.HOLDOUT)
                .map(VariantSpec::variantId)
                .sorted()
                .toList();
    }
}
