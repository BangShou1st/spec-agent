package com.specagent.model.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 文件名:ActiveModelTargetPort.java
 *
 * 用途:"当前激活提供商 + 其选中模型"的只读投影端口。由设置侧实现,供推理侧
 * 做 Run 级模型冻结读取——它只读取既有设置事实,绝不改变任何选择,也不是
 * 第二套模型选择系统。
 */
public interface ActiveModelTargetPort {

    /**
     * 当前激活提供商与其选中模型;未配置或模型为空时返回 {@code Optional.empty()}。
     * 目标不可解析时实现方可抛出具类型的失败,由调用方按"目标不可用"处理。
     */
    Optional<Target> activeTarget();

    default Optional<Target> targetForConnectionReference(String reference) { return Optional.empty(); }

    /**
     * 一次解析出的不可变请求配置。{@code runtimeSettings} 只在本次请求内存中携带
     * 凭据,而 {@code bindingFingerprint} 是不含明文凭据的 SHA-256 配置指纹,可用于
     * Run 绑定持久化。诊断文本只展示提供商和模型。
     */
    record Target(String provider, String model, Object runtimeSettings, String bindingFingerprint) {
        public Target(String provider, String model) {
            this(provider, model, null);
        }

        public Target(String provider, String model, Object runtimeSettings) {
            this(provider, model, runtimeSettings,
                    fingerprint(provider, model, runtimeSettings));
        }

        public Target {
            // A supplied digest is not authority: it must describe this exact
            // immutable snapshot, including credentials, endpoint and revision.
            if (!fingerprint(provider, model, runtimeSettings).equals(bindingFingerprint)) {
                throw new IllegalArgumentException("Model target fingerprint does not match its runtime snapshot");
            }
        }

        @Override
        public String toString() {
            return "Target[provider=" + provider + ", model=" + model + "]";
        }

        private static String fingerprint(String provider, String model, Object settings) {
            List<String> fields = new ArrayList<>();
            fields.add("model-target-v1");
            fields.add(provider);
            fields.add(model);
            if (settings instanceof RuntimeKiloSettings value) {
                fields.add("KILO");
                fields.add(value.selectedModel());
                fields.add(value.apiKey());
                fields.add(value.credentialSource());
                fields.add(value.settingsRevision());
                fields.add(value.baseUrl());
            } else if (settings instanceof RuntimeOpenCodeSettings value) {
                fields.add("OPENCODE_ZEN");
                fields.add(value.selectedModel());
                fields.add(value.apiKey());
                fields.add(value.credentialSource());
            } else if (settings instanceof RuntimeCustomSettings value) {
                fields.add("CUSTOM");
                fields.add(value.selectedModel());
                fields.add(value.apiKey());
                fields.add(value.apiFormat());
                fields.add(value.baseUrl());
                fields.add(Long.toString(value.configRevision()));
                // Preserve the exact historical singleton digest format.
                if (value.providerId() != null) fields.add(value.providerId().toString());
            } else if (settings != null) {
                throw new IllegalArgumentException("Unsupported runtime model target settings");
            }
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                for (String field : fields) {
                    byte[] bytes = field == null ? new byte[0] : field.getBytes(StandardCharsets.UTF_8);
                    digest.update((byte) (field == null ? 0 : 1));
                    digest.update((byte) (bytes.length >>> 24));
                    digest.update((byte) (bytes.length >>> 16));
                    digest.update((byte) (bytes.length >>> 8));
                    digest.update((byte) bytes.length);
                    digest.update(bytes);
                }
                return HexFormat.of().formatHex(digest.digest());
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is unavailable", impossible);
            }
        }
    }
}
