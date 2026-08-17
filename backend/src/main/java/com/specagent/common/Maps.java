package com.specagent.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds a parameter map that allows {@code null} values.
 *
 * <p>{@code Map.of} / {@code Map.entry} reject null keys and values, but runtime
 * records legitimately store nullable columns (e.g. {@code parent_node_id},
 * {@code created_by_run_id}). This helper produces a plain map that
 * {@code NamedParameterJdbcTemplate} can bind, including nulls.
 */
public final class Maps {

    private Maps() {
    }

    public static Map<String, Object> of(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (keyValues == null) {
            return map;
        }
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Maps.of requires an even number of arguments");
        }
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
