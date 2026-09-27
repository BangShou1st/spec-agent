package com.specagent.workspace.context;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:ContextSnapshotTest.java
 *
 * 测试目标:验证 {@link ContextSnapshot} 构造器对列表字段的规范化行为——
 * 所有列表字段都必须容忍 null 入参(规范化为空列表);非空入参则做防御性
 * 拷贝并保证不可修改。历史上 {@code includedPatchIds} 曾因 {@code List.copyOf(null)}
 * 在两个分支上出现 NPE 回归。
 */
class ContextSnapshotTest {

    private ContextSnapshot snapshotWithNullLists() {
        return new ContextSnapshot(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                ContextOperationType.NODE_QUERY,
                null, null, null, null, null, null,
                null, "hash-1", Instant.now());
    }

    @Test
    void nullListArgumentsNormalizeToEmptyLists() {
        ContextSnapshot snapshot = snapshotWithNullLists();
        assertThat(snapshot.includedNodeIds()).isEmpty();
        assertThat(snapshot.includedAnswerIds()).isEmpty();
        assertThat(snapshot.includedPatchIds()).isEmpty();
        assertThat(snapshot.excludedRouteIds()).isEmpty();
        assertThat(snapshot.relatedNodeIds()).isEmpty();
        assertThat(snapshot.relations()).isEmpty();
    }

    @Test
    void presentListsAreDefensivelyCopiedAndUnmodifiable() {
        UUID nodeA = UUID.randomUUID();
        List<UUID> nodes = new java.util.ArrayList<>(List.of(nodeA));
        List<ContextRelation> relations = new java.util.ArrayList<>(List.of(
                new ContextRelation(nodeA, UUID.randomUUID(), "DEPENDS_ON")));

        ContextSnapshot snapshot = new ContextSnapshot(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), nodeA,
                ContextOperationType.NODE_QUERY,
                nodes, List.of(), List.of(), List.of(),
                List.of(nodeA), relations,
                null, "hash-2", Instant.now());

        // 修改调用方持有的列表不得泄漏进快照。
        nodes.add(UUID.randomUUID());
        relations.clear();
        assertThat(snapshot.includedNodeIds()).containsExactly(nodeA);
        assertThat(snapshot.relations()).hasSize(1);
        assertThat(snapshot.relatedNodeIds()).containsExactly(nodeA);
        assertThat(snapshot.includedNodeIds()).isUnmodifiable();
        assertThat(snapshot.relations()).isUnmodifiable();
    }
}