package com.specagent.trace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Sanitizes diagnostic-only semantic evidence before it can leave the
 * process.  This is deliberately independent from the model request path:
 * redaction can only change the copied trace value, never the value sent to a
 * model or applied to runtime state.
 */
public final class SemanticTraceSanitizer {

    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\bbearer\\s+[^\\s,;]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)(\\b(?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|"
                    + "secret|credential)\\b\\s*[:=]\\s*)[^\\s,;]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_SENTINEL = Pattern.compile(
            "super-secret-do-not-log", Pattern.LITERAL);

    private SemanticTraceSanitizer() {
    }

    public static Object sanitize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                sanitized.put(key, sensitiveKey(key)
                        ? "[REDACTED]" : sanitize(entry.getValue()));
            }
            return sanitized;
        }
        if (value instanceof List<?> list) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : list) {
                sanitized.add(sanitize(item));
            }
            return sanitized;
        }
        if (value instanceof String text) {
            String result = SECRET_SENTINEL.matcher(text).replaceAll("[REDACTED]");
            result = result.replace(".local-secrets.env", "[REDACTED_FILE]");
            result = BEARER.matcher(result).replaceAll("Bearer [REDACTED]");
            return KEY_VALUE.matcher(result).replaceAll("$1[REDACTED]");
        }
        return value;
    }

    public static String exceptionSummary(Throwable error) {
        if (error == null) {
            return null;
        }
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.isBlank()
                ? "" : ": " + String.valueOf(sanitize(message)));
    }

    private static boolean sensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("authorization")
                || normalized.contains("bearer")
                || normalized.contains("api_key")
                || normalized.contains("apikey")
                || normalized.contains("access_token")
                || normalized.contains("refresh_token")
                || normalized.equals("token")
                || normalized.contains("secret")
                || normalized.contains("client_secret")
                || normalized.contains("password")
                || normalized.contains("credential")
                || normalized.contains("private_key")
                || normalized.contains("internal_secret");
    }
}
