package com.specagent.assistant.model;

/**
 * 文件名:AssistantTextStreamDecoder.java
 *
 * 用途:面向冻结的模型输出契约的增量展示解码器,服务于流式输出链路——
 * 模型片段一边到达,一边把可对用户展示的正文逐段"放行"给前端。
 *
 * 它只识别通用 JSON 结构:定位顶层的 {@code kind} 与 {@code assistantText}
 * 字符串值,并对文本值做渐进式解码(包括被网络分片截断的转义序列)。
 * 它绝不窥探用户提示词、关键词、能力 ID、项目名或正文语义。
 * 只有当 {@code kind} 完整可读且属于允许用户可见正文的类型
 * (FINAL、CLARIFY、NAVIGATE)时才释放文本;TOOL 草稿与未知 kind 会一直缓冲。
 * 输入畸形或被截断时只是停止释放——控制决策仍以严格的全文档解析器为准(fail-closed)。
 */
public final class AssistantTextStreamDecoder {

    private final StringBuilder raw = new StringBuilder();
    private String kind = null;
    private int emitted = 0;

    public record AppendResult(boolean kindKnown, String kind, String releasableText) {}

    /** 追加一段原始模型片段;返回本次新近可释放的明文。 */
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
                // 只释放"完整的 Unicode 标量值"前缀:末尾若悬着高位代理项,
                // 先扣住等低位代理项配对;孤立的高位或低位代理项绝不释放。
                // 这是展示层的兜底保护;严格的解析器仍是权威。
                releasable = scalarValidPrefix(releasable);
                emitted += releasable.length();
            }
        }
        return new AppendResult(kind != null, kind, releasable);
    }

    /**
     * 只含完整 Unicode 标量值的最大前缀。
     * 高位代理项后面没有字符时先扣住;跟有效低位代理项则成对释放;
     * 后面跟的不是低位代理项,或者出现孤立低位代理项,则释放到它之前为止(fail-closed)。
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

    /** 读取一个完整的顶层字符串成员;未闭合时返回 null。 */
    private static String scanTopLevelString(StringBuilder buf, String key) {
        int at = findMemberValueStart(buf, key);
        if (at < 0) return null;
        StringBuilder out = new StringBuilder();
        return readJsonString(buf, at, out) ? out.toString() : null;
    }

    /**
     * 尽可能多地解码顶层字符串成员中已完成的部分。
     * 遇到不完整的转义或未终止的值就停;成员不存在或不是字符串时返回 null。
     */
    private static String decodeTopLevelStringPrefix(StringBuilder buf, String key) {
        int at = findMemberValueStart(buf, key);
        if (at < 0 || at >= buf.length() || buf.charAt(at) != '"') return null;
        StringBuilder out = new StringBuilder();
        readJsonStringPrefix(buf, at, out);
        return out.toString();
    }

    /** 定位顶层成员值的起始下标;未就绪时返回 -1。 */
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

    /** 字符串闭合引号的下标;未终止时返回 -1。 */
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
     * 持续追加解码出的字符,直到输入在值中途耗尽。
     * 只有当该值根本不是字符串时返回 false;不完整的转义只是暂停等待。
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
