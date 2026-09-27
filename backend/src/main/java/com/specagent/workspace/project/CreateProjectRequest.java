package com.specagent.workspace.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 文件名:CreateProjectRequest.java
 *
 * 用途:创建项目的请求体。只接受用户拥有的内容;运行时拥有的字段
 * ({@code projectId}、{@code activeRouteId}、{@code defaultProfileId}、
 * {@code createdAt}、{@code updatedAt})绝不接受客户端提供。
 */
public record CreateProjectRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 255, message = "must be at most 255 characters")
        String title) {
}