package com.specagent.modelsettings;

import java.time.Instant;
import java.util.UUID;

/**
 * The single active runtime provider.
 *
 * <p>{@code activeProvider} keeps the preset code so existing readers and the
 * preset activation path stay valid; {@code activeProviderId} names the exact
 * row, which is what makes several user-defined providers distinguishable.
 * For a seeded preset row both point at the same provider.
 */
public record ModelProviderSettings(String activeProvider, UUID activeProviderId, Instant updatedAt) {
}
