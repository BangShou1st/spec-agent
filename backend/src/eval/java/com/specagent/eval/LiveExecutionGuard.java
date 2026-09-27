package com.specagent.eval;

import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.decision.RemotePythonDecisionEngine;
import com.specagent.model.contract.ModelInferenceGateway;
import com.specagent.model.provider.OpenCodeModelInferenceGateway;

/**
 * 文件名:LiveExecutionGuard.java
 *
 * 用途:B-live(真实 provider)评测档位的快速失败身份守卫。只有当
 * Java 边界确实连到远程 Python Brain、Java broker 确实连到真实 OpenCode
 * 网关时,"live" 标签才有意义。守卫刻意在评测边界检查具体的生产 bean 身份:
 * 测试用的脚本化 Brain 或伪造推理网关绝不能冒充 live-provider 观察。
 *
 * 协作:由 live 档位的运行链路在执行前调用 {@link #requireRemoteProvider},
 * 通过后返回 {@link Evidence} 存入 {@link ObservationEnvelope}。
 */
public final class LiveExecutionGuard {

    private LiveExecutionGuard() {
    }

    public static Evidence requireRemoteProvider(AgentDecisionEngine decisionEngine,
                                                  ModelInferenceGateway inferenceGateway,
                                                  BrainScriptInstaller scriptedBrain) {
        if (scriptedBrain != null) {
            throw new IllegalStateException(
                    "B-live rejected: ScriptedBrain/BrainScriptInstaller is active");
        }
        if (!(decisionEngine instanceof RemotePythonDecisionEngine)) {
            throw new IllegalStateException(
                    "B-live rejected: AgentDecisionEngine is "
                            + typeName(decisionEngine)
                            + "; expected RemotePythonDecisionEngine");
        }
        if (!(inferenceGateway instanceof OpenCodeModelInferenceGateway)) {
            throw new IllegalStateException(
                    "B-live rejected: ModelInferenceGateway is "
                            + typeName(inferenceGateway)
                            + "; expected OpenCodeModelInferenceGateway"
                            + " (fake providers are not live)");
        }
        return new Evidence(
                decisionEngine.getClass().getName(),
                inferenceGateway.getClass().getName());
    }

    private static String typeName(Object value) {
        return value == null ? "<none>" : value.getClass().getName();
    }

    /** 安全的 bean 身份证据;不含任何凭据或 prompt 数据。 */
    public record Evidence(String decisionEngine, String inferenceGateway) {
    }
}
