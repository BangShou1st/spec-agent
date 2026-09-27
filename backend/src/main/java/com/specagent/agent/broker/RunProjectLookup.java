package com.specagent.agent.broker;

import java.util.UUID;

/**
 * 文件名:RunProjectLookup.java
 *
 * 用途:端口接口,供内部推理 broker 在处理请求前解析 run 的所属项目。
 *
 * provider 侧的会话身份按项目划分,因此 broker 需要知道所服务 run 的
 * 项目。实现位于 runtime 包、委托给持久化存储,使 broker 不依赖任何
 * 仓库(repository)。
 *
 * 协作:未知的 run 返回 {@code null};调用方将其视为"无项目亲和"
 * 并回退为按 run 区分会话,绝不视为请求失败:无法在此解析的 run
 * 早已被 {@link RunExistenceCheck} 拒绝。
 */
@FunctionalInterface
public interface RunProjectLookup {

    /** run 的所属项目;无法解析时返回 {@code null}。 */
    UUID projectIdOf(UUID runId);
}
