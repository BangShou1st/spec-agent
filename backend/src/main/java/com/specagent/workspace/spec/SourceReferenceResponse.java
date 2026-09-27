package com.specagent.workspace.spec;

import com.specagent.workspace.spec.SourceReference;

import java.util.UUID;

/**
 * 文件名:SourceReferenceResponse.java
 *
 * 用途:规格 claim 指向运行时记录的溯源指针的只读响应投影(kind 字符串 +
 * refId),由 {@link SourceReference} 转换而来,供 API 返回给前端展示。
 */
public record SourceReferenceResponse(
        String kind,
        UUID refId) {

    public static SourceReferenceResponse from(SourceReference reference) {
        return new SourceReferenceResponse(reference.kind().code(), reference.refId());
    }
}