package com.specagent.model.provider;

import java.util.List;

/**
 * 文件名:OpenCodeModelList.java
 *
 * 用途:OpenCode Zen {@code GET /models} 载荷的解析结果。线上载荷为
 * {@code {"object":"list","data":[{"id":...,"object":"model",...}]}}(已与线上
 * 端点核对);没有 id 的条目直接跳过,使解析对载荷漂移保持健壮。
 */
public record OpenCodeModelList(List<OpenCodeModel> data) {

    public OpenCodeModelList {
        data = data == null ? List.of() : List.copyOf(data);
    }
}