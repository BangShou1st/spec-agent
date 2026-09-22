package com.specagent.retrieval.index;

import com.specagent.node.Node;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative allow-list boundary before any canonical text enters indexing. */
public class RetrievalSourcePolicy {

    private static final Set<String> SECRET_KEYS = Set.of(
            "password", "secret", "credential", "credentials", "apikey", "api_key",
            "authorization", "access_token", "refresh_token", "private_key");
    private static final Pattern SECRET_TEXT = Pattern.compile(
            "(?i)(-----BEGIN [A-Z ]*PRIVATE KEY-----|authorization\\s*:\\s*bearer\\s+|(?:password|api[_-]?key|access[_-]?token)\\s*[=:])");

    public boolean allow(Node node) {
        if (node == null || node.isRetracted()) {
            return true;
        }
        return node.content().keySet().stream()
                .map(key -> key == null ? "" : key.toLowerCase(Locale.ROOT))
                .noneMatch(SECRET_KEYS::contains);
    }

    public boolean allowText(String text) {
        return text == null || !SECRET_TEXT.matcher(text).find();
    }
}
