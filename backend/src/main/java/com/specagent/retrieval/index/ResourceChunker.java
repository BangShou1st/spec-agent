package com.specagent.retrieval.index;

import java.util.ArrayList;
import java.util.List;

/** Deterministic, source-preserving chunker for text resources. */
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
