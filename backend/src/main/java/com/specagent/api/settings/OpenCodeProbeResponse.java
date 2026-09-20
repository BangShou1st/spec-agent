package com.specagent.api.settings;

import java.util.List;

/**
 * Model discovery projection: every exposed model id plus the free subset.
 * `freeModels` stays for backward compatibility with existing clients.
 */
public record OpenCodeProbeResponse(List<String> allModels, List<String> freeModels) {

    public OpenCodeProbeResponse {
        allModels = allModels == null ? List.of() : List.copyOf(allModels);
        freeModels = freeModels == null ? List.of() : List.copyOf(freeModels);
    }
}
