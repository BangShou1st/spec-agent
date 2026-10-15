package com.specagent.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.specagent.access.identity.SecurityModeAdmission;
import com.specagent.access.identity.SecurityModeRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SecurityProtocolAdmissionTest {
    private final SecurityModeRepository repository = mock(SecurityModeRepository.class);
    private final IdentityModeProperties properties = new IdentityModeProperties();
    private final SecurityModeAdmission admission = new SecurityModeAdmission(repository, properties);

    private void register(String mode, int version, String issuer) {
        when(repository.find()).thenReturn(Optional.of(
                new SecurityModeRepository.RegisteredMode(mode, version, issuer)));
    }

    @Test
    void v13BinaryRejectsOlderAndUnknownFutureProtocolsEvenWithSameMode() {
        for (int version : new int[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 14}) {
            register("LOCAL", version, null);
            assertThatThrownBy(admission::verify)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Security protocol version mismatch");
            assertThat(admission.admitted()).isFalse();
        }
    }

    @Test
    void failedReverificationClearsEarlierAdmission() {
        register("LOCAL", 13, null);
        admission.verify();
        assertThat(admission.admitted()).isTrue();
        register("LOCAL", 1, null);
        assertThatThrownBy(admission::verify).isInstanceOf(IllegalStateException.class);
        assertThat(admission.admitted()).isFalse();
    }

    @Test
    void oidcFleetCannotMixIdentityIssuers() {
        properties.setMode("OIDC");
        properties.setIssuerUri("https://identity-a.example/realm");
        register("OIDC", 13, "https://identity-b.example/realm");
        assertThatThrownBy(admission::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Security issuer admission refused");
        assertThat(admission.admitted()).isFalse();
        register("OIDC", 13, properties.getIssuerUri());
        admission.verify();
        assertThat(admission.admitted()).isTrue();
    }
}
