package com.specagent.model.inference;

import com.specagent.common.InMemoryRunModelBindingPort;
import com.specagent.model.contract.ActiveModelTargetPort.Target;
import com.specagent.model.contract.RuntimeKiloSettings;
import com.specagent.model.contract.RuntimeCustomSettings;
import com.specagent.model.contract.RuntimeOpenCodeSettings;
import com.specagent.model.contract.RuntimeOpenRouterSettings;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ModelTargetBindingContractTest {
    @Test void suppliedDigestMustDescribeTheActualRuntimeSnapshot() {
        var first = new RuntimeKiloSettings("dummy-old", "model-a");
        var changed = new RuntimeKiloSettings("dummy-new", "model-a");
        var target = new Target("KILO", "model-a", first);
        assertThatThrownBy(() -> new Target("KILO", "model-a", changed, target.bindingFingerprint()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("dummy-old").hasMessageNotContaining("dummy-new");
    }

    @Test void matchingExplicitDigestPreservesTheExistingFingerprintFormat() {
        var settings = new RuntimeKiloSettings("dummy-key", "model-a");
        var target = new Target("KILO", "model-a", settings);
        assertThat(new Target("KILO", "model-a", settings, target.bindingFingerprint()))
                .isEqualTo(target);
        assertThat(target.bindingFingerprint()).matches("[0-9a-f]{64}");
        // Frozen v1 fixture: tightening validation must not invalidate existing durable digests.
        assertThat(target.bindingFingerprint()).isEqualTo("39375349ba2ef73358ba7b940c72d90fdb32ea78d02f5e1c8b59db99171bf1eb");
    }

    @Test void allProviderSnapshotsRetainCredentialAndConfigurationIdentity() {
        assertThat(new Target("OPENCODE_ZEN", "model-a", new RuntimeOpenCodeSettings("dummy-a", "model-a"))
                .bindingFingerprint()).isNotEqualTo(new Target("OPENCODE_ZEN", "model-a",
                new RuntimeOpenCodeSettings("dummy-b", "model-a")).bindingFingerprint());
        assertThat(new Target("OPENROUTER", "model-a", new RuntimeOpenRouterSettings("dummy", "model-a", 1))
                .bindingFingerprint()).isNotEqualTo(new Target("OPENROUTER", "model-a",
                new RuntimeOpenRouterSettings("dummy", "model-a", 2)).bindingFingerprint());
        var baseline = new Target("CUSTOM", "model-a", new RuntimeCustomSettings(
                "CHAT_COMPLETIONS", "https://example.invalid/v1", "dummy-a", "model-a", 1));
        for (var changed : java.util.List.of(
                new RuntimeCustomSettings("CHAT_COMPLETIONS", "https://example.invalid/v2", "dummy-a", "model-a", 1),
                new RuntimeCustomSettings("CHAT_COMPLETIONS", "https://example.invalid/v1", "dummy-b", "model-a", 1),
                new RuntimeCustomSettings("RESPONSES", "https://example.invalid/v1", "dummy-a", "model-a", 1),
                new RuntimeCustomSettings("CHAT_COMPLETIONS", "https://example.invalid/v1", "dummy-a", "model-a", 2))) {
            assertThat(new Target("CUSTOM", "model-a", changed).bindingFingerprint())
                    .isNotEqualTo(baseline.bindingFingerprint());
            assertThatThrownBy(() -> new Target("CUSTOM", "model-a", changed, baseline.bindingFingerprint()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void invalidAndLegacySentinelValuesNeverBecomeMemoryBindings() {
        var bindings = new InMemoryRunModelBindingPort();
        UUID run = UUID.randomUUID();
        for (String invalid : Arrays.asList(null, "", "legacy-unbound", "a".repeat(63),
                "a".repeat(65), "A".repeat(64), "g".repeat(64))) {
            assertThat(bindings.bindOrVerify(run, invalid)).isFalse();
        }
        assertThat(bindings.bindOrVerify(null, "a".repeat(64))).isFalse();
        assertThat(bindings.bindOrVerify(run, "a".repeat(64))).isTrue();
        assertThat(bindings.bindOrVerify(run, "a".repeat(64))).isTrue();
        assertThat(bindings.bindOrVerify(run, "b".repeat(64))).isFalse();
    }
}
