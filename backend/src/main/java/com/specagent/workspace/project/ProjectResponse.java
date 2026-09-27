package com.specagent.workspace.project;

import com.specagent.workspace.project.Project;

import java.time.Instant;
import java.util.UUID;

/**
 * 文件名:ProjectResponse.java
 *
 * 用途:项目读取接口返回的完整项目表示。运行时拥有的字段一律只读
 * 暴露,任何一个都不能通过请求提供;{@code activeRouteId} 始终是唯一的
 * 活跃路线指针,绝不从路线生命周期状态推导。
 */
public record ProjectResponse(
        UUID id,
        String title,
        UUID activeRouteId,
        UUID defaultProfileId,
        Instant createdAt,
        Instant updatedAt) {

    public static ProjectResponse from(Project project) {
        return new ProjectResponse(
                project.id(),
                project.title(),
                project.activeRouteId(),
                project.defaultProfileId(),
                project.createdAt(),
                project.updatedAt());
    }
}
