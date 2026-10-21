package com.specagent.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.common.Json;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** SQL submission and CAS/outbox orchestration only. No SQL is executed by these mocks. */
class DeferredCapabilityRepositoryTest {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final com.specagent.common.CapabilityCompletionPort completions = mock(com.specagent.common.CapabilityCompletionPort.class);
    final CapabilityInvocationRepository repository = new CapabilityInvocationRepository(jdbc, new Json(new ObjectMapper()), completions);
    final UUID id = UUID.randomUUID();
    CapabilityResult result(CapabilityResult.Status status) {
        return new CapabilityResult(id, "key", "probe", status, Map.of(), List.of(), Map.of(), List.of());
    }

    @Test void completionDeadlineUsesWallClockAfterPotentiallySlowAuthorization() {
        // Transaction started before deadline, authorization finished after it.
        // SQL must not compare with transaction-start now()/CURRENT_TIMESTAMP.
        repository.completePending(id, result(CapabilityResult.Status.SUCCEEDED));
        verify(jdbc).update(org.mockito.ArgumentMatchers.argThat(sql ->
                sql.contains("pending_deadline > clock_timestamp()")), anyMap());
    }

    @Test void timeoutScanUsesTheSameWallClockAsCompletion() {
        when(jdbc.query(anyString(), anyMap(), org.mockito.ArgumentMatchers.<RowMapper<UUID>>any()))
                .thenReturn(List.of());
        repository.expirePending(10);
        verify(jdbc).query(org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("pending_deadline <= clock_timestamp()") && !sql.contains("now()")),
                anyMap(), org.mockito.ArgumentMatchers.<RowMapper<UUID>>any());
    }

    @Test void onlyOneCompletionWinnerRequestsAnotherContinuationGeneration() {
        when(jdbc.update(contains("pending_deadline > clock_timestamp()"), anyMap())).thenReturn(1, 0);
        assertThat(repository.completePending(id, result(CapabilityResult.Status.SUCCEEDED))).isTrue();
        assertThat(repository.completePending(id, result(CapabilityResult.Status.FAILED))).isFalse();
        verify(completions, times(1)).completed(id);
    }

    @Test void persistenceFailurePropagatesWithoutInventingWakeupAndRetryCanProceed() {
        when(jdbc.update(contains("pending_deadline > clock_timestamp()"), anyMap()))
                .thenThrow(new IllegalStateException("database fixture failure")).thenReturn(1);
        assertThatThrownBy(() -> repository.completePending(id, result(CapabilityResult.Status.SUCCEEDED)))
                .hasMessage("database fixture failure");
        verifyNoInteractions(completions);
        assertThat(repository.completePending(id, result(CapabilityResult.Status.SUCCEEDED))).isTrue();
    }

    @Test void outboxFailureIsNotReportedAsSuccessfulCompletion() {
        when(jdbc.update(contains("pending_deadline > clock_timestamp()"), anyMap())).thenReturn(1);
        doThrow(new IllegalStateException("outbox fixture failure")).when(completions).completed(id);
        assertThatThrownBy(() -> repository.completePending(id, result(CapabilityResult.Status.SUCCEEDED)))
                .hasMessage("outbox fixture failure");
        // Transaction rollback/retry is a PostgreSQL integration requirement, not simulated here.
    }

    @Test void nonterminalOrWrongOwnerResultCannotCompleteAnyInvocation() {
        for (var status : List.of(CapabilityResult.Status.PENDING, CapabilityResult.Status.RUNNING,
                CapabilityResult.Status.IN_PROGRESS, CapabilityResult.Status.REPLAYED)) {
            assertThatThrownBy(() -> repository.complete(id, result(status))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> repository.completePending(UUID.randomUUID(), result(CapabilityResult.Status.SUCCEEDED)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @Test void synchronousCompletionCannotOverwriteATerminalOrPendingRecord() {
        repository.complete(id, result(CapabilityResult.Status.SUCCEEDED));
        verify(jdbc).update(contains("WHERE id = :id AND status = 'RUNNING'"), anyMap());
    }

    @Test void timeoutUsesPersistedDeadlineAndWakesOnlyRowsWonByTheBoundedScan() {
        when(jdbc.query(anyString(), anyMap(), org.mockito.ArgumentMatchers.<RowMapper<UUID>>any()))
                .thenReturn(List.of(id), List.of());
        assertThat(repository.expirePending(10)).isEqualTo(1);
        assertThat(repository.expirePending(10)).isZero();
        verify(jdbc, times(2)).query(contains("FOR UPDATE SKIP LOCKED"), anyMap(),
                org.mockito.ArgumentMatchers.<RowMapper<UUID>>any());
        verify(completions, times(1)).completed(id);
    }

    @Test void registrationRequiresExplicitPendingAndAdapterBoundedDeadline() {
        assertThatThrownBy(() -> repository.markPending(id, result(CapabilityResult.Status.SUCCEEDED), 60))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.markPending(id, result(CapabilityResult.Status.PENDING), 0))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }
}
