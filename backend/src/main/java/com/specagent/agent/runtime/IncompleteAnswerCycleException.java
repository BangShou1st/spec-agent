package com.specagent.agent.runtime;

import java.util.UUID;

/**
 * 文件名:IncompleteAnswerCycleException.java
 *
 * 用途:目标 route 的 tip 上存在一条已持久化的 Answer,但回答后的处理
 * (STATE_UPDATE)从未完成——此时派生任何产物,只能得到一份"悄悄不完整"的
 * 文档,因此直接拒绝执行。
 *
 * 由 artifact cycle 抛出:针对那种在 tip 变成"未处理回答"之前就已入队的
 * run。命令入口面对同样的情形会提前用相同的拒绝码拦截,保证用户看到的是
 * 同一种解释、同一个恢复入口(先恢复保存的回答,再生成)。
 */
public class IncompleteAnswerCycleException extends RuntimeException {

    private final UUID answerId;
    private final UUID routeId;
    private final UUID nodeId;

    public IncompleteAnswerCycleException(String message) {
        this(message, null, null, null);
    }

    public IncompleteAnswerCycleException(String message, UUID answerId,
                                          UUID routeId, UUID nodeId) {
        super(message);
        this.answerId = answerId;
        this.routeId = routeId;
        this.nodeId = nodeId;
    }

    public UUID answerId() {
        return answerId;
    }

    public UUID routeId() {
        return routeId;
    }

    public UUID nodeId() {
        return nodeId;
    }
}
