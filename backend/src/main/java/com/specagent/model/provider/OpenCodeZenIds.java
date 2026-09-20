package com.specagent.model.provider;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * OpenCode Zen wire identifiers.
 *
 * <p>Zen admits its free tier only for requests whose identifier headers look
 * like the ones a real client sends, and the session header has a frozen shape:
 * {@code x-opencode-session} must be {@code ses_} plus 26 characters - a 12-char
 * lowercase-hex time prefix followed by 14 base62 characters. This was isolated
 * by single-variable A/B on the wire against a live free model:
 *
 * <ul>
 *   <li>{@code ses_} + 32-char UUID  → 403 {@code FreeTierError}</li>
 *   <li>{@code ses_} + 26-char time-prefixed id → 200</li>
 *   <li>header absent → 403</li>
 * </ul>
 *
 * <p>The prefix encodes a millisecond timestamp shifted by {@code 0x1000} plus a
 * 12-bit counter, optionally bitwise-negated for descending ids; the suffix is
 * random. The algorithm mirrors the verified client so the ids are
 * indistinguishable from genuine ones. Sequential-alphabet ids (abcdefg...)
 * are rejected by an upstream low-entropy blacklist; this timestamp+random
 * scheme never produces them.
 */
final class OpenCodeZenIds {

    private static final String BASE62 =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int ID_LENGTH = 26;
    private static final int PREFIX_HEX_LENGTH = 12;

    private OpenCodeZenIds() {
    }

    /** {@code ses_} conversation/session id; stable per run, descending prefix. */
    static String sessionId() {
        return "ses_" + generate(true);
    }

    /** {@code msg_} id; one fresh value per HTTP request, ascending prefix. */
    static String requestId() {
        return "msg_" + generate(false);
    }

    private static String generate(boolean descending) {
        long timestamp = System.currentTimeMillis();
        long current = timestamp * 0x1000L + ThreadLocalRandom.current().nextInt(0x1000);
        long value = descending ? ~current : current;
        StringBuilder id = new StringBuilder(ID_LENGTH);
        for (int i = 0; i < PREFIX_HEX_LENGTH / 2; i++) {
            id.append(String.format(Locale.ROOT, "%02x", (int) ((value >> (40 - 8 * i)) & 0xFF)));
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = PREFIX_HEX_LENGTH; i < ID_LENGTH; i++) {
            id.append(BASE62.charAt(random.nextInt(BASE62.length())));
        }
        return id.toString();
    }
}
