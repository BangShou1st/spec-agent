package com.specagent.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文件名:Maps.java
 *
 * 用途:构建允许 null 值的参数 map,供 {@code NamedParameterJdbcTemplate}
 * 绑定 SQL 参数使用。
 *
 * {@code Map.of} / {@code Map.entry} 不接受 null 键和 null 值,但运行时
 * 记录确实存在可空的列(例如 {@code parent_node_id}、
 * {@code created_by_run_id})。本工具生成普通 map,可以携带 null 值直接绑定。
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
