package com.specagent.agent.runevent;

import java.time.Instant;
import java.util.List;

/**
 * 文件名:RunProgressView.java
 *
 * 用途:交给 API 响应的白名单化运行进度读模型。
 *
 * 约束:只暴露组合出的摘要字段——原始事件 payload 绝不离开后端。
 */
public record RunProgressView(String phase, String summary, List<Step> steps) {

    /** 一条可展示的进度步骤;{@code summary}/{@code items} 可能为 null。 */
    public record Step(int sequence, String phase, String event,
                       String summary, List<String> items, Instant at) {
    }
}
