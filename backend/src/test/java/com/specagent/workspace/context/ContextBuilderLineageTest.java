package com.specagent.workspace.context;

import com.specagent.common.Json;
import com.specagent.workspace.graph.NodeRelationRepository;
import com.specagent.workspace.node.NodeRepository;
import com.specagent.workspace.patch.AnswerPatchRepository;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectRepository;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteHistoryResolver;
import com.specagent.workspace.route.RouteLifecycleStatus;
import com.specagent.workspace.route.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文件名:ContextBuilderLineageTest.java
 *
 * 测试目标:验证 {@link ContextBuilder} 从活跃路线构建上下文时,对节点谱系
 * (lineage) 异常的处理必须委托给权威解析器 {@link RouteHistoryResolver}:
 * 环路、节点缺失、深度超限三类失败原样抛出,不允许被静默截断或吞掉,
 * 也不会在失败时写入上下文快照。
 */
@ExtendWith(MockitoExtension.class)
class ContextBuilderLineageTest {

    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private RouteRepository routeRepository;
    @Mock
    private NodeRepository nodeRepository;
    @Mock
    private AnswerPatchRepository answerPatchRepository;
    @Mock
    private RouteHistoryResolver routeHistoryResolver;
    @Mock
    private ContextSnapshotRepository contextSnapshotRepository;
    @Mock
    private NodeRelationRepository nodeRelationRepository;
    @Mock
    private Json json;
    @InjectMocks
    private ContextBuilder contextBuilder;

    private UUID projectId;
    private UUID routeId;
    private UUID tipNodeId;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        routeId = UUID.randomUUID();
        tipNodeId = UUID.randomUUID();
        Project project = new Project(projectId, "p", routeId, null, NOW, NOW);
        Route route = new Route(routeId, projectId, tipNodeId, tipNodeId,
                RouteLifecycleStatus.OPEN, "R", null, null, null, null, NOW, NOW);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
    }

    @Test
    void cycleFailureComesFromAuthoritativeResolverAndIsNotSilentlyTruncated() {
        when(routeHistoryResolver.resolveLineage(tipNodeId))
                .thenThrow(new IllegalStateException("Node lineage contains a cycle"));

        assertThatThrownBy(() -> contextBuilder.buildFromActiveRoute(
                projectId, UUID.randomUUID(), ContextOperationType.NORMAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Node lineage contains a cycle");
        verify(contextSnapshotRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void missingNodeFailureComesFromAuthoritativeResolver() {
        when(routeHistoryResolver.resolveLineage(tipNodeId))
                .thenThrow(new IllegalArgumentException("Node not found: " + tipNodeId));

        assertThatThrownBy(() -> contextBuilder.buildFromActiveRoute(
                projectId, UUID.randomUUID(), ContextOperationType.NORMAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Node not found: " + tipNodeId);
    }

    @Test
    void depthFailureComesFromAuthoritativeResolver() {
        when(routeHistoryResolver.resolveLineage(tipNodeId))
                .thenThrow(new IllegalStateException("Node lineage exceeds maximum depth"));

        assertThatThrownBy(() -> contextBuilder.buildFromActiveRoute(
                projectId, UUID.randomUUID(), ContextOperationType.NORMAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Node lineage exceeds maximum depth");
    }
}
