package com.specagent.workspace.project;

import com.specagent.workspace.project.Project;

import java.time.Instant;
import java.util.UUID;

/**
 * 文件名:ProjectSummaryResponse.java
 *
 * 用途:列表端点使用的精简项目摘要(不含默认画像等完整字段)。
 */
public record ProjectSummaryResponse(
        UUID id,
        String title,
        UUID activeRouteId,
        Instant createdAt,
        Instant updatedAt) {

    public static ProjectSummaryResponse from(Project project) {
        return new ProjectSummaryResponse(
                project.id(),
                project.title(),
                project.activeRouteId(),
                project.createdAt(),
                project.updatedAt());
    }
}