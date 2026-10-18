package com.specagent.model.contract;

/**
 * 文件名:GaModelTarget.java
 *
 * 用途:一次 GA 执行所冻结的提供商无关模型目标:真实激活提供商、模型 id、设置修订号
 * 与已解析的凭据。它由设置侧解析(见 assistant 的 GaExecutionModelTargetResolver),
 * 传输层只消费它,绝不自行读设置或推断提供商。
 *
 * <p>{@code credential} 绝不进入 {@link #toString()},也绝不写进执行表或诊断日志;
 * 它是唯一承载提供商密钥的字段。
 */
public record GaModelTarget(String provider, String model, String settingsRevision, String credential) {

    public GaModelTarget {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("GA model target provider is required");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("GA model target model is required");
        }
        if (settingsRevision == null || settingsRevision.isBlank()) {
            throw new IllegalArgumentException("GA model target settings revision is required");
        }
    }

    public boolean hasCredential() {
        return credential != null && !credential.isBlank();
    }

    /** 防止诊断日志或执行快照泄露提供商密钥。 */
    @Override
    public String toString() {
        return "GaModelTarget[provider=" + provider + ", model=" + model
                + ", settingsRevision=" + settingsRevision
                + ", credential=" + (hasCredential() ? "<present>" : "<none>") + "]";
    }
}
