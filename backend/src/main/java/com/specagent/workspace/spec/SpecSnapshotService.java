package com.specagent.workspace.spec;

import com.specagent.common.Ids;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 文件名:SpecSnapshotService.java
 *
 * 用途:持久化生成的规格快照。规格快照是绑定单个 route tip 与单个上下文
 * 快照的派生产物,不是事实源。已确认的规格 claim 必须携带来源引用,本服务负责
 * 把这些引用一并记录下来,并对外提供查询以供核验。
 */
@Service
public class SpecSnapshotService {

    private final SpecSnapshotRepository specSnapshotRepository;

    public SpecSnapshotService(SpecSnapshotRepository specSnapshotRepository) {
        this.specSnapshotRepository = specSnapshotRepository;
    }

    public SpecSnapshot createSnapshot(UUID projectId,
                                       UUID routeId,
                                       UUID tipNodeId,
                                       UUID contextSnapshotId,
                                       String format,
                                       List<SpecSection> sections,
                                       List<UnresolvedItem> unresolvedItems,
                                       List<SourceReference> sourceRefs,
                                       UUID createdByRunId) {
        UUID snapshotId = Ids.random();
        Instant now = Instant.now();
        SpecSnapshot snapshot = new SpecSnapshot(snapshotId, projectId, routeId, tipNodeId,
                contextSnapshotId, format == null ? "markdown" : format,
                sections, unresolvedItems, sourceRefs, createdByRunId, now);
        specSnapshotRepository.save(snapshot);
        return snapshot;
    }

    public java.util.Optional<SpecSnapshot> getSnapshot(UUID snapshotId) {
        return specSnapshotRepository.findById(snapshotId);
    }

    public List<SpecSnapshot> listByRoute(UUID routeId) {
        return specSnapshotRepository.findByRoute(routeId);
    }
}
