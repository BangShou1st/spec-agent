package com.specagent.retrieval.index;

import com.specagent.workspace.node.Node;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 文件名:RetrievalSourcePolicy.java
 *
 * 用途:任何规范化文本进入检索索引之前的保守放行白名单边界,
 * 防止密码、token、私钥等敏感内容进入模型可见的检索通道。
 *
 * 按两类规则拦截:疑似承载密钥的字段名(键名归一化后匹配),以及
 * 正文中的密钥形态文本(私钥块、Bearer 头、常见 token 前缀等)。
 */
public class RetrievalSourcePolicy {

    private static final Set<String> SECRET_KEYS = Set.of(
            "password", "secret", "credential", "credentials", "apikey",
            "authorization", "accesstoken", "refreshtoken", "privatekey");
    private static final Pattern SECRET_TEXT = Pattern.compile(
            "(?i)(-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----"
                    + "|authorization\\s*:\\s*bearer\\s+\\S+"
                    + "|(?:password|secret|credential|credentials|api[_-]?key|apikey"
                    + "|access[_-]?token|refresh[_-]?token)\\s*[:=]\\s*\\S+"
                    + "|(?:github_pat_|ghp_|sk-[A-Za-z0-9])\\S*)");

    public boolean allow(Node node) {
        if (node == null) {
            return false;
        }
        return node.content().keySet().stream()
                .map(this::normalizeKey)
                .noneMatch(SECRET_KEYS::contains)
                && allowText(node.content().toString());
    }

    public boolean allowText(String text) {
        return text == null || !SECRET_TEXT.matcher(text).find();
    }

    public boolean allowMetadata(Map<String, ?> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, ?> entry : metadata.entrySet()) {
            if (SECRET_KEYS.contains(normalizeKey(entry.getKey()))) {
                return false;
            }
            if (entry.getValue() instanceof String value && !allowText(value)) {
                return false;
            }
            if (entry.getValue() instanceof Map<?, ?> nested) {
                Map<String, Object> child = new java.util.LinkedHashMap<>();
                nested.forEach((key, value) -> child.put(String.valueOf(key), value));
                if (!allowMetadata(child)) {
                    return false;
                }
            }
        }
        return allowText(metadata.toString());
    }

    private String normalizeKey(String key) {
        return key == null ? "" : key.toLowerCase(Locale.ROOT).replaceAll("[-_]", "");
    }
}
