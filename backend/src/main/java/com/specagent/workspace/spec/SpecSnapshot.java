package com.specagent.workspace.spec;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 文件名:SpecSnapshot.java
 *
 * 用途:为某个 route tip 生成的一份规格快照,是 spec 包的核心领域对象。
 * 它是派生产物,不是事实源:绑定唯一的 route tip 和唯一的上下文快照,已确认的
 * claim 携带溯源引用(source references),保证每段规格内容都能追溯到运行时
 * 记录。快照一旦生成即冻结,后续导出与展示都以它为准。
 */
public class SpecSnapshot {

    private final UUID id;
    private final UUID projectId;
    private final UUID routeId;
    private final UUID tipNodeId;
    private final UUID contextSnapshotId;
    private final String format;
    private final List<SpecSection> sections;
    private final List<UnresolvedItem> unresolvedItems;
    private final List<SourceReference> sourceRefs;
    private final UUID createdByRunId;
    private final Instant createdAt;

    public SpecSnapshot(UUID id,
                        UUID projectId,
                        UUID routeId,
                        UUID tipNodeId,
                        UUID contextSnapshotId,
                        String format,
                        List<SpecSection> sections,
                        List<UnresolvedItem> unresolvedItems,
                        List<SourceReference> sourceRefs,
                        UUID createdByRunId,
                        Instant createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.routeId = routeId;
        this.tipNodeId = tipNodeId;
        this.contextSnapshotId = contextSnapshotId;
        this.format = format;
        this.sections = sections == null ? List.of() : List.copyOf(sections);
        this.unresolvedItems = unresolvedItems == null ? List.of() : List.copyOf(unresolvedItems);
        this.sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        this.createdByRunId = createdByRunId;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public UUID projectId() {
        return projectId;
    }

    public UUID routeId() {
        return routeId;
    }

    public UUID tipNodeId() {
        return tipNodeId;
    }

    public UUID contextSnapshotId() {
        return contextSnapshotId;
    }

    public String format() {
        return format;
    }

    public List<SpecSection> sections() {
        return sections;
    }

    public List<UnresolvedItem> unresolvedItems() {
        return unresolvedItems;
    }

    public List<SourceReference> sourceRefs() {
        return sourceRefs;
    }

    public UUID createdByRunId() {
        return createdByRunId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public boolean hasSourceReferences() {
        return !sourceRefs.isEmpty();
    }
}
