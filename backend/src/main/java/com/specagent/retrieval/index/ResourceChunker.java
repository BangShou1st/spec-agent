package com.specagent.retrieval.index;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件名:ResourceChunker.java
 *
 * 用途:文本资源的确定性分块器。把长文本切成带位置信息的 Chunk
 * (索引、内容、起止字符),供检索索引按块建条目。
 *
 * 切分结果是确定且保源的:同一输入永远得到同样的分块,并且每块都能
 * 映射回原文位置。优先按段落/标题/句子边界切,避免语义被硬截断。
 */
public class ResourceChunker {

    private static final int TARGET_CHARS = 1200;
    private static final int OVERLAP_CHARS = 140;

    public List<Chunk> chunk(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n').strip();
        List<Chunk> result = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (start < text.length()) {
            int targetEnd = Math.min(text.length(), start + TARGET_CHARS);
            int end = targetEnd == text.length() ? targetEnd : boundary(text, start, targetEnd);
            if (end <= start) {
                end = targetEnd;
            }
            String content = text.substring(start, end).strip();
            if (!content.isBlank()) {
                result.add(new Chunk(index++, content, start + 1, end));
            }
            if (end >= text.length()) {
                break;
            }
            start = Math.max(start + 1, end - OVERLAP_CHARS);
        }
        return List.copyOf(result);
    }

    private int boundary(String text, int start, int targetEnd) {
        int paragraph = text.lastIndexOf("\n\n", targetEnd);
        if (paragraph > start + 300) {
            return paragraph + 2;
        }
        int heading = text.lastIndexOf('\n', targetEnd);
        if (heading > start + 300) {
            return heading + 1;
        }
        int sentence = Math.max(text.lastIndexOf('。', targetEnd),
                Math.max(text.lastIndexOf('.', targetEnd), text.lastIndexOf('！', targetEnd)));
        return sentence > start + 300 ? sentence + 1 : targetEnd;
    }

    public record Chunk(int index, String content, int startChar, int endChar) {
    }
}
