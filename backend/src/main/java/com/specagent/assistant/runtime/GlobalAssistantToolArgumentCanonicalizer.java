package com.specagent.assistant.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * 文件名:GlobalAssistantToolArgumentCanonicalizer.java
 *
 * 用途:把工具参数确定性地规范化成稳定字符串,供幂等键、
 * 重复调用检测与轨迹比对使用。Map 键顺序不同不应改变语义,
 * 这里统一按键排序序列化。
 */
@Component
public class GlobalAssistantToolArgumentCanonicalizer {
    private final ObjectMapper mapper;
    public GlobalAssistantToolArgumentCanonicalizer(ObjectMapper mapper) {
        this.mapper = mapper;
    }
    public String canonicalize(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        try {
            return mapper.writeValueAsString(new TreeMap<>(arguments));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to canonicalize tool arguments", ex);
        }
    }
}
