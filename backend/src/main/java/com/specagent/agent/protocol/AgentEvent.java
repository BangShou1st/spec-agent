package com.specagent.agent.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * 文件名:AgentEvent.java
 *
 * 用途:触发本次决策周期的用户/操作者事件,携带操作类型(kind)
 * 以及属于该事件本身的原始输入;绝不携带推导出来的全局历史。
 *
 * 约束:persistenceIntent 是 Runtime 侧授权,由 Java 填写并随事件
 * 传入,Brain 不得自行发明;序列化时为 null 的字段不输出。
 */
public record AgentEvent(String kind,
                           UUID anchorNodeId,
                           UUID selectedOptionId,
                           String freeText,
                           @JsonInclude(JsonInclude.Include.NON_NULL)
                           PersistenceIntent persistenceIntent) {

    public AgentEvent(String kind,
                      UUID anchorNodeId,
                      UUID selectedOptionId,
                      String freeText) {
        this(kind, anchorNodeId, selectedOptionId, freeText, null);
    }

    /** 随触发事件一起传递的、由 Runtime 掌控的授权意图。 */
    public enum PersistenceIntent {
        RECORD_DECISION_NODE
    }
}
