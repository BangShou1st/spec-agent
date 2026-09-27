package com.specagent.agent.runtime;

import java.util.UUID;

/**
 * 文件名:AnswerCycleResult.java
 *
 * 用途:单次回答循环(2 次调用收敛路径)的执行结果。携带该循环产出的
 * 持久化制品 id(answer、patch、节点等)与最终状态,供调用方断言结果或展示。
 */
public record AnswerCycleResult(UUID runId,
                                UUID answerId,
                                UUID patchId,
                                UUID producedNodeId,
                                String status) {
}
