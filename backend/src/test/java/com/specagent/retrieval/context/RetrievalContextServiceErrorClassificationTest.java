package com.specagent.retrieval.context;

import com.specagent.retrieval.search.HybridRetriever;
import com.specagent.workspace.context.ContextOperationType;
import com.specagent.workspace.context.ContextSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class RetrievalContextServiceErrorClassificationTest {

    private HybridRetriever retriever;
    private RetrievalContextService service;
    private ContextSnapshot snapshot;

    @BeforeEach
    void setUp() {
        retriever = Mockito.mock(HybridRetriever.class);
        service = new RetrievalContextService(retriever);

        UUID projectId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        UUID tipNodeId = UUID.randomUUID();

        snapshot = new ContextSnapshot(
                UUID.randomUUID(),
                projectId,
                routeId,
                tipNodeId,
                ContextOperationType.NORMAL,
                List.of(tipNodeId),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                "ctx-hash-123",
                Instant.now()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "RETRIEVAL_UNAVAILABLE",
            "OLLAMA_UNAVAILABLE",
            "DEADLINE_EXCEEDED",
            "EMBEDDING_CONNECTION_FAILED",
            "EMBEDDING_TIMEOUT",
            "EMBEDDING_BUSY",
            "EMBEDDING_INTERRUPTED",
            "EMBEDDING_NOT_CONFIGURED",
            "EMBEDDING_PROVIDER_FAILED"
    })
    @DisplayName("可降级错误：判断为可降级，retrieveWithStatus 标记 supplementalRetrievalUnavailable=true")
    void degradableErrorsShouldDegradeGracefully(String errorCode) {
        assertThat(RetrievalContextService.isDegradable(new IllegalStateException(errorCode))).isTrue();

        when(retriever.usesSharedPython()).thenReturn(true);
        when(retriever.retrieveOutcome(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException(errorCode));

        var outcome = service.retrieveWithStatus(snapshot, Set.of(), "query text");
        assertThat(outcome.items()).isEmpty();
        assertThat(outcome.state()).containsEntry("supplementalRetrievalUnavailable", true);
        assertThat(outcome.state()).containsEntry("retrievalEngineVersion", "python-rag.v1");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "STALE_SCOPE_GRANT",
            "STALE_WORKLOAD",
            "SOURCE_VERSION_MISMATCH",
            "INDEX_GENERATION_MISMATCH",
            "UNSUPPORTED_PROFILE",
            "INVALID_VECTOR",
            "RETRIEVAL_PROTOCOL_ERROR"
    })
    @DisplayName("不可降级错误：安全、完整性与版本不一致错误必须失败关闭 (Fail-Closed)")
    void securityAndIntegrityErrorsMustFailClosed(String errorCode) {
        assertThat(RetrievalContextService.isDegradable(new IllegalStateException(errorCode))).isFalse();

        when(retriever.usesSharedPython()).thenReturn(true);
        when(retriever.retrieveOutcome(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException(errorCode));

        assertThatThrownBy(() -> service.retrieveWithStatus(snapshot, Set.of(), "query text"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(errorCode);
    }

    @Test
    @DisplayName("正常成功零命中：不应误报为 supplementalRetrievalUnavailable=true")
    void successfulZeroHitDoesNotReportUnavailable() {
        when(retriever.usesSharedPython()).thenReturn(true);
        when(retriever.retrieveOutcome(any(), any(), any(), any()))
                .thenReturn(new HybridRetriever.Outcome(List.of(), Map.of(
                        "retrievalEngineVersion", "python-rag.v1",
                        "profileId", "test-profile",
                        "indexGeneration", UUID.randomUUID().toString(),
                        "vectorUnavailable", false,
                        "supplementalRetrievalUnavailable", false
                )));

        var outcome = service.retrieveWithStatus(snapshot, Set.of(), "query text");
        assertThat(outcome.items()).isEmpty();
        assertThat(outcome.state()).containsEntry("supplementalRetrievalUnavailable", false);
    }
}
