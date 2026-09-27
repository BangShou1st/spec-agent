package com.specagent.agent.protocol;

import java.util.Set;

/**
 * 文件名:AgentProtocol.java
 *
 * 用途:跨语言 Agent 边界的冻结协议常量集中地(版本号、调用类型、
 * 事件类型、内部令牌头等)。
 *
 * 约束:这些值是与 Python Brain 共享的带版本线上(wire)契约的一部分
 * (见 {@code contracts/README.md});任何一侧遇到未知协议版本都必须
 * fail-closed 拒绝。
 */
public final class AgentProtocol {

    public static final String INPUT_PROTOCOL_VERSION_V2 = "agent-input.v2";
    public static final String INPUT_PROTOCOL_VERSION_V3 = "agent-input.v3";

    /** 默认的旧版请求协议版本;携带 eligibility 的决策走 V3。 */
    public static final String INPUT_PROTOCOL_VERSION = INPUT_PROTOCOL_VERSION_V2;

    public static final String DECISION_PROTOCOL_VERSION_V2 = "agent-decision.v2";
    public static final String DECISION_PROTOCOL_VERSION_V3 = "agent-decision.v3";

    /** 默认的旧版响应协议版本;携带 eligibility 的决策走 V3。 */
    public static final String DECISION_PROTOCOL_VERSION = DECISION_PROTOCOL_VERSION_V2;

    /** 派生产物(artifact)生成的响应信封版本。 */
    public static final String ARTIFACT_PROTOCOL_VERSION = "agent-artifact.v1";

    /** 内部模型推理代理(Python → Spring)的契约版本。 */
    public static final String INFERENCE_PROTOCOL_VERSION = "model-inference.v1";

    /** 双向通信共用的内部共享密钥请求头。 */
    public static final String INTERNAL_TOKEN_HEADER = "X-Spec-Agent-Internal-Token";

    /** Brain 调用类型的封闭集合;由所调用的端点决定具体类型。 */
    public static final Set<String> CALL_TYPES = Set.of(
            "STATE_UPDATE", "DECISION", "ARTIFACT_GENERATION");

    /** Runtime 可以发送给 Brain 的事件类型的封闭集合。 */
    public static final Set<String> EVENT_KINDS = Set.of(
            "INITIAL", "CONTINUE", "ANSWER_SUBMITTED", "NODE_QUERY");

    private AgentProtocol() {
    }
}
