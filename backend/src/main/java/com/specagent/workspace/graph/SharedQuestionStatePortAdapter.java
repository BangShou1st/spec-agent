package com.specagent.workspace.graph;

import com.specagent.common.SharedQuestionStatePort;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 文件名:SharedQuestionStatePortAdapter.java
 *
 * 用途:answer 包消费的 {@link SharedQuestionStatePort} 在图侧的薄实现,
 * 底层委托给 {@link GraphInvariantValidator}。
 *
 * 它自身不新增任何逻辑:规则就是既有的 {@code validateSharedQuestionState}
 * 方法,包括其冲突码与 fail-closed 行为。它的唯一职责是保住端口的依赖
 * 方向,让 answer 包永远不 import graph 包。
 */
@Component
public class SharedQuestionStatePortAdapter implements SharedQuestionStatePort {

    private final GraphInvariantValidator invariantValidator;

    public SharedQuestionStatePortAdapter(GraphInvariantValidator invariantValidator) {
        this.invariantValidator = invariantValidator;
    }

    @Override
    public void validateSharedQuestionState(UUID projectId, UUID nodeId) {
        invariantValidator.validateSharedQuestionState(projectId, nodeId);
    }
}
