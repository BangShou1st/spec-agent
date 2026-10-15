package com.specagent.runtime.execution;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeScopeMutexTest {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final RuntimeScopeMutex mutex = new RuntimeScopeMutex(jdbc);
    @AfterEach void resetTransactionFlag() { TransactionSynchronizationManager.setActualTransactionActive(false); }

    @Test void autoCommitCannotPretendToHoldScopeProtection() {
        assertThatThrownBy(() -> mutex.tryLock(List.of("project:a"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> mutex.lock(List.of("project:a"))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(jdbc);
    }

    @Test void contentionStopsImmediatelyAndNeverIssuesBlockingLock() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(jdbc.queryForObject(anyString(),anyMap(),eq(Boolean.class))).thenReturn(false);
        assertThat(mutex.tryLock(List.of("project:a","project:b"))).isFalse();
        verify(jdbc,times(1)).queryForObject(contains("pg_try_advisory_xact_lock"),anyMap(),eq(Boolean.class));
        verifyNoMoreInteractions(jdbc);
    }

    @Test void multipleScopesUseSameSortedDeduplicatedOrderForEveryCaller() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(jdbc.queryForObject(anyString(),anyMap(),eq(Boolean.class))).thenReturn(true);
        assertThat(mutex.tryLock(List.of("project:z","ga-thread:a","project:z"))).isTrue();
        @SuppressWarnings("rawtypes") ArgumentCaptor<Map> captured = ArgumentCaptor.forClass(Map.class);
        verify(jdbc,times(2)).queryForObject(anyString(),captured.capture(),eq(Boolean.class));
        assertThat(captured.getAllValues()).extracting(parameters -> parameters.get("scope"))
                .containsExactly("ga-thread:a","project:z");
    }

    @Test void unknownLockResultFailsClosed() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThat(mutex.tryLock(List.of("project:a"))).isFalse();
    }

    @Test void missingScopeKeysCannotReachDatabase() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> mutex.tryLock(java.util.Arrays.asList("project:a",null)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @Test void boundedWaitNormalizesDatabaseFailureAndDoesNotTryToUseAbortedTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(jdbc.queryForObject(eq("SHOW lock_timeout"),anyMap(),eq(String.class))).thenReturn("1s");
        when(jdbc.queryForObject(contains("FROM pg_settings"),anyMap(),eq(String.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("unsafe SQL details"));
        assertThatThrownBy(() -> mutex.lock(List.of("project:a")))
                .isInstanceOf(ExecutionScopeUnavailableException.class).hasNoCause()
                .hasMessage("Execution scope is temporarily unavailable");
        verify(jdbc,never()).queryForObject(contains(":timeout"),anyMap(),eq(String.class));
    }
}
