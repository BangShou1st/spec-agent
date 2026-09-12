package com.specagent.model.provider;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Single boundary for protocol routing. Agent code never branches on
 * {@link CustomApiFormat}; it asks this registry once.
 */
@Component
public class ProtocolAdapterRegistry {

    private final Map<CustomApiFormat, ProtocolAdapter> adapters;

    public ProtocolAdapterRegistry(List<ProtocolAdapter> all) {
        Map<CustomApiFormat, ProtocolAdapter> map = new EnumMap<>(CustomApiFormat.class);
        for (ProtocolAdapter adapter : all) {
            map.put(adapter.format(), adapter);
        }
        if (!map.keySet().containsAll(List.of(CustomApiFormat.values()))) {
            throw new IllegalStateException("Missing protocol adapter registration");
        }
        this.adapters = Map.copyOf(map);
    }

    public ProtocolAdapter require(CustomApiFormat format) {
        ProtocolAdapter adapter = adapters.get(format);
        if (adapter == null) {
            throw new IllegalArgumentException("Unsupported api format: " + format);
        }
        return adapter;
    }
}
