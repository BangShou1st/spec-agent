package com.specagent.model.provider;

import java.util.List;
import java.util.Map;

/** Safe structural diagnostics only: no prompt, content, argument, ID or reasoning values. */
public final class GaNativeStreamException extends IllegalArgumentException {
    private final List<Map<String, Object>> frames;
    public GaNativeStreamException(String stage, List<Map<String, Object>> frames) {
        super("GA_NATIVE_STREAM_" + stage);
        this.frames = List.copyOf(frames);
    }
    public List<Map<String, Object>> frames() { return frames; }
}
