package com.specagent.globalassistant.model;

/**
 * Incremental presentation decoder for the frozen model-output contract.
 *
 * <p>Understands ONLY generic JSON structure: it locates the top-level
 * {@code kind} and {@code assistantText} string values and progressively
 * decodes the text value (including escapes split across fragments). It never
 * inspects user prompts, keywords, capability ids, project names, or prose
 * meaning. Text is released only after {@code kind} is fully known and allows
 * user-visible prose (FINAL, CLARIFY, NAVIGATE); TOOL drafts and unknown
 * kinds stay buffered forever. Malformed or truncated input simply stops
 * releasing; the strict full-document parser remains the fail-closed
 * authority for control decisions.
 */
public final class AssistantTextStreamDecoder {

    private final StringBuilder raw = new StringBuilder();
    private String kind = null;
    private int emitted = 0;

    public record AppendResult(boolean kindKnown, String kind, String releasableText) {}

    /** Appends one raw provider fragment; returns newly releasable plaintext. */
    public AppendResult append(String fragment) {
        if (fragment != null && !fragment.isEmpty()) {
            raw.append(fragment);
        }
        if (kind == null) {
            kind = scanTopLevelString(raw, "kind");
        }
        String releasable = "";
        if (kind != null && (kind.equals("FINAL") || kind.equals("CLARIFY") || kind.equals("NAVIGATE"))) {
            String decoded = decodeTopLevelStringPrefix(raw, "assistantText");
            if (decoded != null && decoded.length() > emitted) {
                releasable = decoded.substring(emitted);
                // Release only the maximal Unicode-scalar-valid prefix: a
                // trailing high surrogate is held for its low half, and an
                // unpaired high or lone low surrogate is never released.
                // Presentation-only fail-safe; the strict parser stays authoritative.
                releasable = scalarValidPrefix(releasable);
                emitted += releasable.length();
            }
        }
        return new AppendResult(kind != null, kind, releasable);
    }

    /**
     * Maximal prefix containing only complete Unicode scalar values.
     * A high surrogate with no following char is held; with a valid low
     * surrogate it is released as a pair; with a non-low follower, or for
     * a standalone low surrogate, release stops before it (fail-closed).
     */
    private static String scalarValidPrefix(String s) {
        int end = s.length();
        for (int i = 0; i < end; ) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= end || !Character.isLowSurrogate(s.charAt(i + 1))) {
                    end = i;
                    break;
                }
                i += 2;
            } else if (Character.isLowSurrogate(c)) {
                end = i;
                break;
            } else {
                i++;
            }
        }
        return s.substring(0, end);
    }

    /** Reads a complete top-level string member; null unless fully closed. */
    private static String scanTopLevelString(StringBuilder buf, String key) {
        int at = findMemberValueStart(buf, key);
        if (at < 0) return null;
        StringBuilder out = new StringBuilder();
        return readJsonString(buf, at, out) ? out.toString() : null;
    }

    /**
     * Decodes as much of a top-level string member as is complete.
     * Stops at an incomplete escape or unterminated value; null when the
     * member is absent or is not a string.
     */
    private static String decodeTopLevelStringPrefix(StringBuilder buf, String key) {
        int at = findMemberValueStart(buf, key);
        if (at < 0 || at >= buf.length() || buf.charAt(at) != '"') return null;
        StringBuilder out = new StringBuilder();
        readJsonStringPrefix(buf, at, out);
        return out.toString();
    }

    /** Index of a top-level member value start, or -1 while incomplete. */
    private static int findMemberValueStart(StringBuilder buf, String key) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        int i = 0;
        int n = buf.length();
        while (i < n) {
            char c = buf.charAt(i);
            if (inString) {
                if (escaped) { escaped = false; }
                else if (c == '\\') { escaped = true; }
                else if (c == '"') { inString = false; }
                i++;
                continue;
            }
            if (c == '"') {
                int keyEnd = scanStringEnd(buf, i);
                if (keyEnd < 0) return -1;
                String candidate = unescapeSimple(buf, i + 1, keyEnd);
                int j = keyEnd + 1;
                while (j < n && isJsonSpace(buf.charAt(j))) j++;
                if (j < n && buf.charAt(j) == ':' && depth == 1 && key.equals(candidate)) {
                    int v = j + 1;
                    while (v < n && isJsonSpace(buf.charAt(v))) v++;
                    return v < n ? v : -1;
                }
                i = keyEnd + 1;
                continue;
            }
            if (c == '{') depth++;
            else if (c == '}') depth--;
            i++;
        }
        return -1;
    }

    private static boolean isJsonSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    /** End index of the closing quote, or -1 while unterminated. */
    private static int scanStringEnd(StringBuilder buf, int open) {
        boolean escaped = false;
        for (int i = open + 1; i < buf.length(); i++) {
            char c = buf.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '"') return i;
        }
        return -1;
    }

    private static boolean readJsonString(StringBuilder buf, int open, StringBuilder out) {
        if (open >= buf.length() || buf.charAt(open) != '"') return false;
        int before = out.length();
        if (!readJsonStringPrefix(buf, open, out)) { out.setLength(before); return false; }
        int end = scanStringEnd(buf, open);
        if (end < 0) { out.setLength(before); return false; }
        return true;
    }

    /**
     * Appends decoded chars until input runs out mid-value. Returns false only
     * when the value is not a string at all; an incomplete escape simply pauses.
     */
    private static boolean readJsonStringPrefix(StringBuilder buf, int open, StringBuilder out) {
        int i = open + 1;
        int n = buf.length();
        while (i < n) {
            char c = buf.charAt(i);
            if (c == '"') return true;
            if (c != '\\') { out.append(c); i++; continue; }
            if (i + 1 >= n) return true;
            char e = buf.charAt(i + 1);
            switch (e) {
                case '"': out.append('"'); i += 2; break;
                case '\\': out.append('\\'); i += 2; break;
                case '/': out.append('/'); i += 2; break;
                case 'b': out.append('\b'); i += 2; break;
                case 'f': out.append('\f'); i += 2; break;
                case 'n': out.append('\n'); i += 2; break;
                case 'r': out.append('\r'); i += 2; break;
                case 't': out.append('\t'); i += 2; break;
                case 'u':
                    if (i + 5 >= n) return true;
                    int code = 0;
                    for (int k = i + 2; k <= i + 5; k++) {
                        int d = Character.digit(buf.charAt(k), 16);
                        if (d < 0) return false;
                        code = (code << 4) + d;
                    }
                    out.append((char) code);
                    i += 6;
                    break;
                default: return false;
            }
        }
        return true;
    }

    private static String unescapeSimple(StringBuilder buf, int from, int to) {
        StringBuilder out = new StringBuilder();
        int i = from;
        while (i < to) {
            char c = buf.charAt(i);
            if (c == '\\' && i + 1 < to) { i += 2; out.append(buf.charAt(i - 1)); continue; }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
