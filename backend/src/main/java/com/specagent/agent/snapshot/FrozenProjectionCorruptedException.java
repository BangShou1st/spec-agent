package com.specagent.agent.snapshot;

/**
 * 文件名:FrozenProjectionCorruptedException.java
 *
 * 用途:持久化的冻结输入投影未通过校验时抛出:哈希不匹配、投影版本
 * 不受支持、payload 无法解析,或 payload 属于另一个 snapshot 身份。
 * 一律 fail-closed——冻结投影是审计与可复现性证据,因此绝不基于活记录
 * 静默重建,也绝不被覆盖。
 *
 * 协作:由 AgentInputSnapshotBuilder 在回放冻结投影的校验阶段抛出。
 */
public class FrozenProjectionCorruptedException extends RuntimeException {

    public FrozenProjectionCorruptedException(String message) {
        super(message);
    }
}
