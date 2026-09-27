package com.specagent.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 文件名:Json.java
 *
 * 用途:Jackson 的轻量封装,负责把结构化内容序列化为 JSON 字符串、
 * 再反序列化回来,供写入 JSONB 列时使用。
 *
 * 运行时把领域中立的结构化内容(claims、options、来源引用、trace 摘要)
 * 以 JSONB 形式落库;这里绝不存放供应商密钥或模型原生的响应对象。
 */
@Component
public class Json {

    private final ObjectMapper mapper;

    public Json(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize JSON content", e);
        }
    }

    public String writeList(List<?> value) {
        if (value == null) {
            return "[]";
        }
        return write(value);
    }

    public <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse JSON content", e);
        }
    }

    public <T> T read(String json, TypeReference<T> type) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse JSON content", e);
        }
    }

    public <T> List<T> readList(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return Collections.emptyList();
        }
        try {
            List<T> result = mapper.readValue(json, type);
            return result == null ? Collections.emptyList() : result;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse JSON list content", e);
        }
    }
}
