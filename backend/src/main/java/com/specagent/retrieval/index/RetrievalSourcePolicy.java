package com.specagent.retrieval.index;

import com.specagent.workspace.node.Node;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative allow-list boundary before any canonical text enters indexing. */
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
