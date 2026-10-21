package com.specagent.agent.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.util.Arrays;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Mockito only: verifies input refusal before JDBC, not PostgreSQL transaction semantics. */
class AgentRunModelBindingRepositoryOfflineTest {
    @Test void bindingSqlWhitelistsRealNonterminalStatesAndNeverReplacesAnExistingDigest() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        new AgentRunModelBindingRepository(jdbc).bindOrVerify(UUID.randomUUID(), "a".repeat(64));
        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), org.mockito.ArgumentMatchers.<String, Object>anyMap());
        assertThat(sql.getValue()).contains(
                "status IN ('created', 'running', 'context_built', 'model_called', 'reflected', 'persisted')",
                "COALESCE(model_binding_fingerprint, :fingerprint)",
                "OR model_binding_fingerprint = :fingerprint")
                .doesNotContain("awaiting_approval");
    }

    @Test void approvalSuccessorHasAnIndependentBindingAndCannotChangeItsOrigin() {
        var bindings = new com.specagent.common.InMemoryRunModelBindingPort();
        UUID origin = UUID.randomUUID(), successor = UUID.randomUUID();
        assertThat(bindings.bindOrVerify(origin, "a".repeat(64))).isTrue();
        assertThat(bindings.bindOrVerify(successor, "b".repeat(64))).isTrue();
        assertThat(bindings.bindOrVerify(origin, "b".repeat(64))).isFalse();
        assertThat(bindings.bindOrVerify(origin, "a".repeat(64))).isTrue();
    }
    @Test void invalidFingerprintAndMissingRunNeverReachJdbc() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var bindings = new AgentRunModelBindingRepository(jdbc);
        for (String invalid : Arrays.asList(null, "", "legacy-unbound", "a".repeat(63),
                "a".repeat(65), "A".repeat(64), "g".repeat(64))) {
            assertThat(bindings.bindOrVerify(UUID.randomUUID(), invalid)).isFalse();
        }
        assertThat(bindings.bindOrVerify(null, "a".repeat(64))).isFalse();
        verifyNoInteractions(jdbc);
    }

    @Test void databaseWinnerIsRequiredAndEachCallIssuesOnlyOneUpdate() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.<String, Object>anyMap())).thenReturn(0, 1);
        var bindings = new AgentRunModelBindingRepository(jdbc);
        UUID run = UUID.randomUUID();
        assertThat(bindings.bindOrVerify(run, "a".repeat(64))).isFalse();
        assertThat(bindings.bindOrVerify(run, "a".repeat(64))).isTrue();
        verify(jdbc, times(2)).update(anyString(), org.mockito.ArgumentMatchers.<String, Object>anyMap());
        verifyNoMoreInteractions(jdbc);
    }

    @Test void jdbcFailurePropagatesWithoutRetryOrInventedSuccess() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var failure = new IllegalStateException("offline JDBC failure fixture");
        when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.<String, Object>anyMap())).thenThrow(failure);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new AgentRunModelBindingRepository(jdbc).bindOrVerify(UUID.randomUUID(), "a".repeat(64)))
                .isSameAs(failure);
        verify(jdbc).update(anyString(), org.mockito.ArgumentMatchers.<String, Object>anyMap());
        verifyNoMoreInteractions(jdbc);
    }
}
