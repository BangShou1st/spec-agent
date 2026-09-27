package com.specagent.workspace.spec;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * 文件名:SourceReference.java
 *
 * 用途:从规格 claim 指向运行时记录的溯源指针(kind + refId)。已确认的
 * claim 必须携带至少一条来源引用,使规格可以追溯到节点、回答、补丁、上下文
 * 快照或 route——这是规格可审计、可复现的关键。
 */
public class SourceReference {

    private final SourceKind kind;
    private final UUID refId;

    @JsonCreator
    public SourceReference(@JsonProperty("kind") SourceKind kind,
                           @JsonProperty("refId") UUID refId) {
        this.kind = kind;
        this.refId = refId;
    }

    public static SourceReference of(SourceKind kind, UUID refId) {
        return new SourceReference(kind, refId);
    }

    @JsonProperty("kind")
    public SourceKind kind() {
        return kind;
    }

    @JsonProperty("refId")
    public UUID refId() {
        return refId;
    }
}
