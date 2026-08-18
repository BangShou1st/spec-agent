package com.specagent.support;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the live smoke environment gate. Pure logic: no database,
 * no network, no process environment mutation.
 */
class LiveSmokeEnvironmentTest {

    private static Map<String, String> env(String key, String gateway, String model) {
        return Map.of(
                LiveSmokeEnvironment.KEY_ENV, key,
                LiveSmokeEnvironment.GATEWAY_ENV, gateway,
                LiveSmokeEnvironment.MODEL_ENV, model);
    }

    @Test
    void missingKeyIsBlockedWithClearReason() {
        LiveSmokeEnvironment.Readiness readiness =
                LiveSmokeEnvironment.check(Map.of(
                        LiveSmokeEnvironment.GATEWAY_ENV, "opencode",
                        LiveSmokeEnvironment.MODEL_ENV, "mimo-v2.5-free"));

        assertThat(readiness.ready()).isFalse();
        assertThat(readiness.blockers())
                .anySatisfy(blocker -> assertThat(blocker).contains("missing SPEC_AGENT_OPENCODE_KEY"));
    }

    @Test
    void wrongGatewaySelectorIsBlocked() {
        LiveSmokeEnvironment.Readiness readiness =
                LiveSmokeEnvironment.check(env("sk-key", "fake", "mimo-v2.5-free"));

        assertThat(readiness.ready()).isFalse();
        assertThat(readiness.blockers())
                .anySatisfy(blocker -> assertThat(blocker)
                        .contains("SPEC_AGENT_MODEL_GATEWAY must be opencode"));
    }

    @Test
    void unsetGatewaySelectorIsBlocked() {
        LiveSmokeEnvironment.Readiness readiness =
                LiveSmokeEnvironment.check(Map.of(
                        LiveSmokeEnvironment.KEY_ENV, "sk-key",
                        LiveSmokeEnvironment.MODEL_ENV, "mimo-v2.5-free"));

        assertThat(readiness.ready()).isFalse();
        assertThat(readiness.blockers())
                .anySatisfy(blocker -> assertThat(blocker).contains("must be opencode"));
    }

    @Test
    void nonFreeSelectedModelIsBlocked() {
        LiveSmokeEnvironment.Readiness readiness =
                LiveSmokeEnvironment.check(env("sk-key", "opencode", "some-paid-model"));

        assertThat(readiness.ready()).isFalse();
        assertThat(readiness.blockers())
                .anySatisfy(blocker -> assertThat(blocker).contains("must end with -free"));
    }

    @Test
    void missingModelDefaultsToFreeModel() {
        LiveSmokeEnvironment.Readiness readiness =
                LiveSmokeEnvironment.check(Map.of(
                        LiveSmokeEnvironment.KEY_ENV, "sk-key",
                        LiveSmokeEnvironment.GATEWAY_ENV, "opencode"));

        assertThat(readiness.ready()).isTrue();
        assertThat(readiness.selectedModel()).isEqualTo(LiveSmokeEnvironment.DEFAULT_MODEL);
    }

    @Test
    void completeEnvironmentIsReady() {
        LiveSmokeEnvironment.Readiness readiness =
                LiveSmokeEnvironment.check(env("sk-abcd1234", "opencode", "mimo-v2.5-free"));

        assertThat(readiness.ready()).isTrue();
        assertThat(readiness.blockers()).isEmpty();
        assertThat(readiness.gateway()).isEqualTo("opencode");
        assertThat(readiness.selectedModel()).isEqualTo("mimo-v2.5-free");
        assertThat(readiness.maskedSuffix()).isEqualTo("1234");
    }

    @Test
    void maskSuffixNeverReturnsTheFullKey() {
        String key = "sk-abcdef1234";

        String masked = LiveSmokeEnvironment.maskSuffix(key);

        assertThat(masked).isEqualTo("1234");
        assertThat(masked).doesNotContain("sk-");
        assertThat(masked).hasSizeLessThan(key.length());
        assertThat(LiveSmokeEnvironment.maskSuffix(null)).isEqualTo("?");
        assertThat(LiveSmokeEnvironment.maskSuffix("   ")).isEqualTo("?");
        // A short secret must not leak its full value.
        assertThat(LiveSmokeEnvironment.maskSuffix("abcd")).isEqualTo("?");
        assertThat(LiveSmokeEnvironment.maskSuffix("ab")).isEqualTo("?");
    }
}
