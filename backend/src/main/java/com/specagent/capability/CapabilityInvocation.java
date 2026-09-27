package com.specagent.capability;

import java.util.Map;
import java.util.UUID;

/**
 * 文件名:CapabilityInvocation.java
 *
 * 用途:一次被接受的动作所请求的能力调用。invocationKey 是运行时持有的
 * 幂等元数据:相同的 key 重放时会直接返回已记录的结果,而不是重新执行副作用。
 *
 * @param invocationId  调用的唯一 ID
 * @param invocationKey 幂等键,相同 key 的重放不重复执行
 * @param capabilityId  要执行的能力标识
 * @param projectId     所属项目 ID
 * @param runId         所属运行 ID
 * @param arguments     调用参数
 */
public record CapabilityInvocation(
        UUID invocationId,
        String invocationKey,
        String capabilityId,
        UUID projectId,
        UUID runId,
        Map<String, Object> arguments) {

    public CapabilityInvocation {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
