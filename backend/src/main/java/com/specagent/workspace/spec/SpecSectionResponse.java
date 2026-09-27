package com.specagent.workspace.spec;

import com.specagent.workspace.spec.SpecSection;

/**
 * 文件名:SpecSectionResponse.java
 *
 * 用途:规格快照中单个章节的只读响应投影(id + 标题 + 正文),由
 * {@link SpecSection} 转换而来,供 API 返回给前端展示。
 */
public record SpecSectionResponse(
        String id,
        String title,
        String content) {

    public static SpecSectionResponse from(SpecSection section) {
        return new SpecSectionResponse(section.id(), section.title(), section.content());
    }
}