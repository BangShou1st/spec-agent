package com.specagent.model.provider;

import java.util.List;

/**
 * Parsed {@code GET /models} payload from OpenCode Zen.
 *
 * <p>The wire payload is {@code {"object":"list","data":[{"id":...,"object":"model",...}]}}
 * as verified against the live endpoint; entries without an id are skipped so
 * the parse is robust to payload drift.
 */
public record OpenCodeModelList(List<OpenCodeModel> data) {

    public OpenCodeModelList {
        data = data == null ? List.of() : List.copyOf(data);
    }
}