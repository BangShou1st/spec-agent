package com.specagent.modelsettings;

/**
 * 文件名:ApiKeyMasking.java
 *
 * 用途:模型连接密钥的<b>统一脱敏与比较原语</b>。此前同一套语义散落在
 * ModelProvidersService / CustomProviderSettingsService / KiloSettingsService /
 * OpenCodeSettingsService 四处各自维护(其中 Kilo/OpenCode 两处对短密钥返回
 * 空串而非 null),MS-CROSS-R1 的短密钥防泄漏修复必须同时核对四个副本。
 *
 * <p>统一语义(fail closed):
 * <ul>
 *   <li>{@link #suffix}:密钥缺失或长度 ≤ 4 时一律返回 {@code null}——过短
 *       密钥的原文绝不能作为自己的脱敏后缀出现(空串与 null 在各读取点的
 *       mask 归一后外部表现一致,历史已存空串行同样被归一);</li>
 *   <li>{@link #hasKey}:null 与空白都视为无密钥;</li>
 *   <li>{@link #equalsNullable}:null 安全的字符串等值比较。</li>
 * </ul>
 *
 * <p>展示层的掩码格式("••••"+后缀 / 无密钥占位)属于各设置服务自己的
 * UI 契约,刻意留在各自实现中,不在本类统一。
 */
final class ApiKeyMasking {

    private ApiKeyMasking() {}

    /** 密钥末 4 位作为脱敏后缀;缺失或过短(≤4)返回 null,短密钥原文绝不外泄。 */
    static String suffix(String apiKey) {
        if (apiKey == null || apiKey.length() <= 4) {
            return null;
        }
        return apiKey.substring(apiKey.length() - 4);
    }

    /** null 与空白都视为无密钥。 */
    static boolean hasKey(String apiKey) {
        return apiKey != null && !apiKey.isBlank();
    }

    /** null 安全等值:双方 null 相等,单方 null 不等,否则按内容比较。 */
    static boolean equalsNullable(String a, String b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b);
    }
}
