package com.specagent.workspace.route;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 文件名:ReplacementOptionRequest.java
 *
 * 用途:重新生成请求中的一个 replacement 选项。只接受客户端拥有的
 * 内容(label、impact);选项 id 由运行时创建,永远不由客户端提供。
 */
public record ReplacementOptionRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 500, message = "must be at most 500 characters")
        String label,
        @Size(max = 2000, message = "must be at most 2000 characters")
        String impact) {
}