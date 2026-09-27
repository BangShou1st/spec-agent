package com.specagent.model.provider;

/**
 * 文件名:OpenCodeModel.java
 *
 * 用途:OpenCode Zen {@code GET /models} 返回载荷中的一条模型条目(id + 归属方)。
 */
public record OpenCodeModel(String id, String ownedBy) {

    public OpenCodeModel {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("model id is required");
        }
    }
}