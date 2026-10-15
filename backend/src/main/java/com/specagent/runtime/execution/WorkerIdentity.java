package com.specagent.runtime.execution;

import java.util.UUID;

/**
 * 文件名:WorkerIdentity.java
 *
 * 用途:本进程的执行身份。每次进程启动生成一个新的随机 UUID——
 * 绝不能用固定主机名冒充执行身份(design §5.1):主机的旧进程可能仍在运行,
 * 而固定的名字会让新进程误认领旧进程的租约。
 *
 * <p>workerId 参与一切 token 校验,因此"进程重启"天然等价于"新执行身份",
 * 旧 token 立即失效。
 */
public final class WorkerIdentity {

    private final UUID id = UUID.randomUUID();

    public UUID id() {
        return id;
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
