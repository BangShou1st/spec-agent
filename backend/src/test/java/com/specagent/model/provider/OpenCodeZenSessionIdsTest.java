package com.specagent.model.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The header values here are a wire contract, not cosmetics: Zen answers 403
 * {@code FreeTierError} for a UUID-shaped session and 200 for the client shape,
 * verified by single-variable A/B against a live free model.
 */
class OpenCodeZenSessionIdsTest {

    /** 12 hex chars of time prefix followed by 14 base62 chars. */
    private static final String CLIENT_SHAPE = "[0-9a-f]{12}[0-9A-Za-z]{14}";

    @Test
    void conversationSessionIsStableAndCarriesTheClientShape() {
        UUID projectId = UUID.fromString("12345678-1234-1234-1234-123456789abc");

        String first = OpenCodeZenSessionIds.forConversation(projectId);
        String second = OpenCodeZenSessionIds.forConversation(projectId);

        // One project keeps one provider session, so every request inside it
        // continues the same conversation instead of opening a new one.
        assertThat(first).isEqualTo(second);
        assertThat(first).startsWith("ses_");
        assertThat(first.substring("ses_".length())).hasSize(26).matches(CLIENT_SHAPE);
        assertThat(OpenCodeZenSessionIds.forConversation(UUID.randomUUID())).isNotEqualTo(first);
    }

    @Test
    void nullConversationIdFailsClosed() {
        assertThatThrownBy(() -> OpenCodeZenSessionIds.forConversation(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ephemeralSessionsAreNonBlankSingleLineUniqueAndClientShaped() {
        String first = OpenCodeZenSessionIds.newEphemeral();
        String second = OpenCodeZenSessionIds.newEphemeral();

        assertThat(first).isNotBlank().startsWith("ses_");
        assertThat(first).doesNotContain("\n", "\r");
        assertThat(first.substring("ses_".length())).hasSize(26).matches(CLIENT_SHAPE);
        assertThat(second).isNotBlank().startsWith("ses_");
        assertThat(second).isNotEqualTo(first);
    }
}
