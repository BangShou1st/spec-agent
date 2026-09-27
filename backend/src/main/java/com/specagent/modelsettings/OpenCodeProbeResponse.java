package com.specagent.modelsettings;

import java.util.List;

/**
 * 文件名:OpenCodeProbeResponse.java
 *
 * 用途:OpenCode 模型发现的响应投影:全部可用模型 id 与其中的免费子集。
 * {@code freeModels} 字段保留是为了兼容既有客户端。
 */
public record OpenCodeProbeResponse(List<String> allModels, List<String> freeModels) {

    public OpenCodeProbeResponse {
        allModels = allModels == null ? List.of() : List.copyOf(allModels);
        freeModels = freeModels == null ? List.of() : List.copyOf(freeModels);
    }
}
