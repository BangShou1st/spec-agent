package com.specagent.workspace.answer;

import com.specagent.common.AnswerExistencePort;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 文件名:AnswerExistencePortAdapter.java
 *
 * 用途:供 graph 包消费的 {@link AnswerExistencePort} 的薄适配器,底层委托
 * {@link AnswerRepository} 实现。自身不附加任何逻辑:存在性判断就是既有的
 * {@code existsByNodeId} 查询。它存在的唯一意义是维持端口依赖方向——graph 包
 * 永远不直接 import answer 包,避免包间耦合成环。
 */
@Component
public class AnswerExistencePortAdapter implements AnswerExistencePort {

    private final AnswerRepository answerRepository;

    public AnswerExistencePortAdapter(AnswerRepository answerRepository) {
        this.answerRepository = answerRepository;
    }

    @Override
    public boolean nodeHasFinalizedAnswer(UUID nodeId) {
        return answerRepository.existsByNodeId(nodeId);
    }
}
