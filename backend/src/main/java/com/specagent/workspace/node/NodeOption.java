package com.specagent.workspace.node;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.specagent.common.Ids;

import java.util.UUID;

/**
 * 文件名:NodeOption.java
 *
 * 用途:澄清节点上呈现的一个可选项。选项是节点不可变提示的一部分,
 * 节点创建后不可编辑。{@code recommended} 标记模型基于上下文的建议——
 * 只是展示给用户的参考,绝不是预选答案。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class NodeOption {

    private final UUID id;
    private final String label;
    private final String impact;
    private final boolean recommended;

    @JsonCreator
    public NodeOption(@JsonProperty("id") UUID id,
                      @JsonProperty("label") String label,
                      @JsonProperty("impact") String impact) {
        this(id, label, impact, false);
    }

    public NodeOption(UUID id, String label, String impact, boolean recommended) {
        this.id = id;
        this.label = label;
        this.impact = impact;
        this.recommended = recommended;
    }

    public static NodeOption of(String label, String impact) {
        return new NodeOption(Ids.random(), label, impact, false);
    }

    @JsonProperty("id")
    public UUID id() {
        return id;
    }

    @JsonProperty("label")
    public String label() {
        return label;
    }

    @JsonProperty("impact")
    public String impact() {
        return impact;
    }

    @JsonProperty("recommended")
    public boolean recommended() {
        return recommended;
    }
}
