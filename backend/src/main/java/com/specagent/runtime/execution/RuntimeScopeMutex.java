package com.specagent.runtime.execution;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Stable transaction mutexes also cover an execution scope with no owner row yet. */
@Component
public class RuntimeScopeMutex {
    private static final long HASH_SEED = 736_251_904_117L;
    private final NamedParameterJdbcTemplate jdbc;

    public RuntimeScopeMutex(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean tryLock(Collection<String> scopes) {
        requireTransaction();
        for (String scope : ordered(scopes)) {
            if (!Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT pg_try_advisory_xact_lock(hashtextextended(:scope,:seed))",
                    Map.of("scope", scope, "seed", HASH_SEED), Boolean.class))) return false;
        }
        return true;
    }

    /** Legacy execution can be claimed while a manual transaction owns the mutex: wait briefly before context/model work. */
    public void lock(Collection<String> scopes) {
        requireTransaction();
        List<String> keys = ordered(scopes);
        try {
            String previous = jdbc.queryForObject("SHOW lock_timeout", Map.of(), String.class);
            // Retain a stricter caller timeout; never extend the protected transaction's existing bound.
            jdbc.queryForObject("""
                    SELECT set_config('lock_timeout', CASE WHEN setting::bigint=0 OR setting::bigint>3000
                        THEN '3000ms' ELSE setting || 'ms' END, true)
                    FROM pg_settings WHERE name='lock_timeout'
                    """, Map.of(), String.class);
            for (String scope : keys) jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:scope,:seed))",
                    Map.of("scope", scope, "seed", HASH_SEED), rs -> { });
            jdbc.queryForObject("SELECT set_config('lock_timeout',:timeout,true)", Map.of("timeout", previous), String.class);
        } catch (org.springframework.dao.DataAccessException ex) {
            // PostgreSQL has aborted the transaction; do not attempt further SQL or preserve an unsafe provider error.
            throw new ExecutionScopeUnavailableException();
        }
    }

    private static List<String> ordered(Collection<String> scopes) {
        if (scopes == null || scopes.stream().anyMatch(key -> key == null || key.isBlank()))
            throw new IllegalArgumentException("Execution scopes are required");
        return scopes.stream().distinct().sorted().toList();
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Execution scope mutex requires an active transaction");
    }
}
