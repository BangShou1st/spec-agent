package com.specagent.workspace.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 文件名:RenameProjectRequest.java
 *
 * 用途:重命名项目的请求体;标题规则与创建时一致。
 */
public record RenameProjectRequest(
        @NotBlank
        @Size(max = 255)
        String title) {
}
