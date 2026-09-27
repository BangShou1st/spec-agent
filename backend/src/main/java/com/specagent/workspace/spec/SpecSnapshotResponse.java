package com.specagent.workspace.spec;

import com.specagent.workspace.spec.SpecSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 文件名:SpecSnapshotResponse.java
 *
 * 用途:规格快照的只读响应表示(DTO)。规格快照是派生产物,绝非事实源;
 * DTO 只暴露溯源信息与内容,原始模型/提供商响应、原始提示词、全局需求状态和
 * 数据库内部结构一律不外露。
 */
public record SpecSnapshotResponse(
        UUID id,
        UUID projectId,
        UUID routeId,
        UUID tipNodeId,
        UUID contextSnapshotId,
        String format,
        List<SpecSectionResponse> sections,
        List<UnresolvedItemResponse> unresolvedItems,
        List<SourceReferenceResponse> sourceRefs,
        UUID createdByRunId,
        Instant createdAt) {

    public static SpecSnapshotResponse from(SpecSnapshot snapshot) {
        return new SpecSnapshotResponse(
                snapshot.id(),
                snapshot.projectId(),
                snapshot.routeId(),
                snapshot.tipNodeId(),
                snapshot.contextSnapshotId(),
                snapshot.format(),
                snapshot.sections().stream().map(SpecSectionResponse::from).toList(),
                snapshot.unresolvedItems().stream().map(UnresolvedItemResponse::from).toList(),
                snapshot.sourceRefs().stream().map(SourceReferenceResponse::from).toList(),
                snapshot.createdByRunId(),
                snapshot.createdAt());
    }
}