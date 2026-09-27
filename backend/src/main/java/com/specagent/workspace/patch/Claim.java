package com.specagent.workspace.patch;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.specagent.common.Ids;

import java.util.UUID;

/**
 * 文件名:Claim.java
 *
 * 用途:由答案推导出的领域中立的 需求 claim(断言)。claim 是一段
 * 结构化的需求状态。它始终可以追溯到产生它的节点与答案,在已确认
 * (confirmed)的情况下还可追溯到来源引用。claim 是重放构建
 * {@code RequirementState} 的最小单元。
 */
public class Claim {

    private final UUID id;
    private final ClaimKind kind;
    private final String text;
    private final ClaimStatus status;
    private final Double confidence;
    private final UUID sourceNodeId;
    private final UUID sourceAnswerId;

    @JsonCreator
    public Claim(@JsonProperty("id") UUID id,
                 @JsonProperty("kind") ClaimKind kind,
                 @JsonProperty("text") String text,
                 @JsonProperty("status") ClaimStatus status,
                 @JsonProperty("confidence") Double confidence,
                 @JsonProperty("sourceNodeId") UUID sourceNodeId,
                 @JsonProperty("sourceAnswerId") UUID sourceAnswerId) {
        this.id = id;
        this.kind = kind;
        this.text = text;
        this.status = status;
        this.confidence = confidence;
        this.sourceNodeId = sourceNodeId;
        this.sourceAnswerId = sourceAnswerId;
    }

    public static Claim of(ClaimKind kind, String text, ClaimStatus status, UUID sourceNodeId, UUID sourceAnswerId) {
        return new Claim(Ids.random(), kind, text, status, null, sourceNodeId, sourceAnswerId);
    }

    /**
     * 由结构化模型输出推导、由运行时持有的 claim:id 由运行时分配,
     * 出处留给运行时稍后落地。模型输出绝不允许提供 id、sourceNodeId
     * 或 sourceAnswerId。
     */
    public static Claim unsourced(ClaimKind kind, String text, ClaimStatus status, Double confidence) {
        return new Claim(Ids.random(), kind, text, status, confidence, null, null);
    }

    public Claim withId(UUID newId) {
        return new Claim(newId, kind, text, status, confidence, sourceNodeId, sourceAnswerId);
    }

    @JsonProperty("id")
    public UUID id() {
        return id;
    }

    @JsonProperty("kind")
    public ClaimKind kind() {
        return kind;
    }

    @JsonProperty("text")
    public String text() {
        return text;
    }

    @JsonProperty("status")
    public ClaimStatus status() {
        return status;
    }

    @JsonProperty("confidence")
    public Double confidence() {
        return confidence;
    }

    @JsonProperty("sourceNodeId")
    public UUID sourceNodeId() {
        return sourceNodeId;
    }

    @JsonProperty("sourceAnswerId")
    public UUID sourceAnswerId() {
        return sourceAnswerId;
    }

    /**
     * 供调用方使用的便捷助手;绝不会进入持久化的 claim JSON。
     */
    @JsonIgnore
    public boolean isConfirmed() {
        return status == ClaimStatus.CONFIRMED;
    }
}
