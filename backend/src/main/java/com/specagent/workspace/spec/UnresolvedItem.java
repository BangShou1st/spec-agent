package com.specagent.workspace.spec;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 文件名:UnresolvedItem.java
 *
 * 用途:规格快照中尚未确认的事项(text + category)。缺少运行时支撑或
 * 不确定的内容必须标记为假定、建议、风险或未决,绝不允许冒充已确认内容——
 * 这是"未处理回答阻断规格生成"约束的另一面:含糊之处必须显式暴露。
 */
public class UnresolvedItem {

    private final String text;
    private final String category;

    @JsonCreator
    public UnresolvedItem(@JsonProperty("text") String text,
                          @JsonProperty("category") String category) {
        this.text = text;
        this.category = category;
    }

    public static UnresolvedItem of(String text, String category) {
        return new UnresolvedItem(text, category);
    }

    @JsonProperty("text")
    public String text() {
        return text;
    }

    @JsonProperty("category")
    public String category() {
        return category;
    }
}
