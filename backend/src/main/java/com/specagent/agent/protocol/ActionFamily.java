package com.specagent.agent.protocol;

/**
 * 文件名:ActionFamily.java
 *
 * 用途:决策可以提出的通用动作家族的封闭集合(Brain → Runtime 契约的一部分)。
 *
 * 约束:家族刻意保持领域中立,属于产品机制层;业务能力只能通过
 * payload 语义或能力描述符(CapabilityDescriptor)注入,绝不允许新增
 * 动作名。Stage A 只校验家族合法性与 payload 形状,Stage A worker 不执行任何家族。
 */
public enum ActionFamily {
    CREATE_NODE,
    UPDATE_NODE,
    CONNECT_NODE,
    CREATE_ROUTE,
    REQUEST_USER_INPUT,
    RESPOND_TO_USER,
    INVOKE_CAPABILITY,
    GENERATE_ARTIFACT,
    WAIT;

    public String code() {
        return name();
    }

    public static ActionFamily fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Action family must not be blank");
        }
        try {
            return ActionFamily.valueOf(code);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown action family: " + code);
        }
    }
}
