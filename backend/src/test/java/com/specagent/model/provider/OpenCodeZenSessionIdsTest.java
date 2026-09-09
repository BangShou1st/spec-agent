package com.specagent.model.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class OpenCodeZenSessionIdsTest {

    @Test
    void runDerivedSessionHasFrozenFormat() {
        UUID runId = UUID.fromString("12345678-1234-1234-1234-123456789abc");

        assertThat(OpenCodeZenSessionIds.forRun(runId))
                .isEqualTo("ses_12345678123412341234123456789abc");
    }

    @Test
    void nullRunIdFailsClosed() {
        assertThatThrownBy(() -> OpenCodeZenSessionIds.forRun(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ephemeralSessionsAreNonBlankSingleLineAndUnique() {
        String first = OpenCodeZenSessionIds.newEphemeral();
        String second = OpenCodeZenSessionIds.newEphemeral();

        assertThat(first).isNotBlank().startsWith("ses_");
        assertThat(first).doesNotContain("\n", "\r");
        assertThat(second).isNotBlank().startsWith("ses_");
        assertThat(second).isNotEqualTo(first);
    }
}
