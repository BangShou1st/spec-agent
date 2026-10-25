package com.specagent.model.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Catalog pricing is informational; unknown pricing never means free. */
public final class ModelPriceMetadata {
    private ModelPriceMetadata() {}

    public static List<String> explicitlyFreeIds(JsonNode root, List<String> modelIds) {
        var free = new ArrayList<String>();
        for (JsonNode item : root.path("data")) {
            String id = item.path("id").asText("");
            JsonNode pricing = item.path("pricing");
            if (!modelIds.contains(id) || !pricing.isObject()
                    || !pricing.hasNonNull("prompt") || !pricing.hasNonNull("completion")) continue;
            boolean zero = true;
            var values = pricing.elements();
            while (values.hasNext()) {
                try {
                    if (new BigDecimal(values.next().asText()).compareTo(BigDecimal.ZERO) != 0) zero = false;
                } catch (NumberFormatException ex) {
                    zero = false;
                }
            }
            if (zero) free.add(id);
        }
        return List.copyOf(free);
    }
}
