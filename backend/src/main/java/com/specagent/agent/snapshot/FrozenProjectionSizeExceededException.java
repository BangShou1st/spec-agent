package com.specagent.agent.snapshot;

/**
 * 文件名:FrozenProjectionSizeExceededException.java
 *
 * 用途:规范化的冻结输入投影超出配置的体积上限时抛出。
 * fail-closed 而不是截断:被静默截断的 payload 绝不能继续代表该
 * snapshot 的冻结身份。
 *
 * 协作:由 AgentInputSnapshotBuilder 在首次冻结写库前做体积检查时抛出。
 */
public class FrozenProjectionSizeExceededException extends RuntimeException {

    public FrozenProjectionSizeExceededException(String message) {
        super(message);
    }
}
