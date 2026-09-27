package com.specagent.workspace.spec;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 文件名:SpecSection.java
 *
 * 用途:生成的规格快照中的一个章节(id + 标题 + 正文)。章节是派生输出;
 * 已确认的内容必须能通过快照的来源引用(source references)追溯到运行时记录。
 */
public class SpecSection {

    private final String id;
    private final String title;
    private final String content;

    @JsonCreator
    public SpecSection(@JsonProperty("id") String id,
                       @JsonProperty("title") String title,
                       @JsonProperty("content") String content) {
        this.id = id;
        this.title = title;
        this.content = content;
    }

    public static SpecSection of(String title, String content) {
        return new SpecSection(java.util.UUID.randomUUID().toString(), title, content);
    }

    @JsonProperty("id")
    public String id() {
        return id;
    }

    @JsonProperty("title")
    public String title() {
        return title;
    }

    @JsonProperty("content")
    public String content() {
        return content;
    }
}
