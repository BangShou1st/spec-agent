package com.specagent.runtime.execution;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL, separate threads/connections and transaction lifetimes; no provider calls. */
@SpringBootTest
@ActiveProfiles("test")
class RuntimeScopeMutexIntegrationTest {
    @Autowired RuntimeScopeMutex mutex;
    @Autowired PlatformTransactionManager manager;
    @Autowired NamedParameterJdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private ExecutorService pool;
    private final String scope=RuntimeExecutionProtocol.projectScope(UUID.randomUUID());

    @BeforeEach void setup() {transaction=new TransactionTemplate(manager);pool=Executors.newSingleThreadExecutor();}
    @AfterEach void cleanup() throws Exception {pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}

    @Test void emptyScopeConflictIsNonblockingButDifferentProjectIsIndependent() {
        transaction.executeWithoutResult(status->{
            assertThat(mutex.tryLock(List.of(scope))).isTrue();
            assertThat(mutex.tryLock(List.of(scope))).isTrue(); // Same transaction is reentrant.
            assertThat(otherTry(scope)).isFalse();
            assertThat(otherTry(RuntimeExecutionProtocol.projectScope(UUID.randomUUID()))).isTrue();
        });
        assertThat(otherTry(scope)).isTrue();
    }

    @Test void rollbackReleasesMutexWithoutPersistentOwnerCleanup() {
        transaction.executeWithoutResult(status->{
            assertThat(mutex.tryLock(List.of(scope))).isTrue();
            status.setRollbackOnly();
        });
        assertThat(otherTry(scope)).isTrue();
    }

    @Test void stricterCallerTimeoutBoundsLegacyWaitAndRollsBack() {
        transaction.executeWithoutResult(status->{
            assertThat(mutex.tryLock(List.of(scope))).isTrue();
            var waiter=pool.submit(()->transaction.execute(tx->{
                jdbc.queryForObject("SELECT set_config('lock_timeout','250ms',true)",Map.of(),String.class);
                mutex.lock(List.of(scope));
                return true;
            }));
            assertThatThrownBy(()->waiter.get(2,TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasCauseInstanceOf(ExecutionScopeUnavailableException.class);
        });
        assertThat(otherTry(scope)).isTrue();
    }

    @Test void successfulLegacyWaitRestoresCallerTimeout() {
        transaction.executeWithoutResult(status->{
            jdbc.queryForObject("SELECT set_config('lock_timeout','50ms',true)",Map.of(),String.class);
            String before=jdbc.queryForObject("SHOW lock_timeout",Map.of(),String.class);
            mutex.lock(List.of(scope));
            assertThat(jdbc.queryForObject("SHOW lock_timeout",Map.of(),String.class)).isEqualTo(before);
            assertThat(otherTry(scope)).isFalse();
        });
    }

    private boolean otherTry(String key) {
        try {return pool.submit(()->transaction.execute(tx->mutex.tryLock(List.of(key)))).get(2,TimeUnit.SECONDS);}
        catch(Exception ex) {throw new AssertionError("scope ownership must be decided within a bounded wait",ex);}
    }
}
