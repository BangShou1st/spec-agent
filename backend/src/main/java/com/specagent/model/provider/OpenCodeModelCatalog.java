package com.specagent.model.provider;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Dynamic OpenCode Zen model discovery.
 *
 * <p>Free models are discovered from the live {@code GET /models} payload, never
 * from a hardcoded production list: whatever OpenCode currently marks free is
 * returned, so the provider can add or retire free models without code changes.
 *
 * <p>The free marker follows the observed live payload (checked against
 * {@code https://opencode.ai/zen/v1/models}): free model ids end with the
 * {@code -free} suffix.
 */
@Component
public class OpenCodeModelCatalog {

    private final OpenCodeZenTransport transport;

    public OpenCodeModelCatalog(OpenCodeZenTransport transport) {
        this.transport = transport;
    }

    /**
     * Returns the ids of the models OpenCode currently exposes as free.
     *
     * @param apiKey optional bearer credential; may be null or blank because
     *               model discovery is public
     */
    public List<String> listFreeModels(String apiKey) {
        return transport.listModels(apiKey).data().stream()
                .filter(model -> isFreeModel(model.id()))
                .map(OpenCodeModel::id)
                .toList();
    }

    private static boolean isFreeModel(String modelId) {
        return modelId != null && modelId.endsWith("-free");
    }
}