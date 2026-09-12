package com.specagent.settings.provider;

import java.time.Instant;

public record ModelProviderSettings(String activeProvider, Instant updatedAt) {
}
