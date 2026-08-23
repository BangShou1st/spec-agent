package com.specagent.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Thin wrapper around Jackson for persisting structured JSONB columns.
 *
 * <p>Runtime code stores domain-neutral structured content (claims, options,
 * source references, trace summaries) as JSONB. It never stores provider
 * secrets or model-native response objects here.
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
