package com.specagent.workspace.spec;

import com.specagent.workspace.spec.UnresolvedItem;

/**
 * 文件名:UnresolvedItemResponse.java
 *
 * 用途:规格快照中未决事项的只读响应投影(text + category),由
 * {@link UnresolvedItem} 转换而来,供 API 返回给前端展示。
 */
public record UnresolvedItemResponse(
        String text,
        String category) {

    public static UnresolvedItemResponse from(UnresolvedItem item) {
        return new UnresolvedItemResponse(item.text(), item.category());
    }
}