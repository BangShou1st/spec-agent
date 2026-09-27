package com.specagent.assistant.model;

import com.specagent.model.provider.CompatibilityDecisionSemantics;
import org.springframework.stereotype.Service;

/**
 * 文件名:GlobalAssistantDecisionSemanticsAdapter.java
 *
 * 用途:把供应商层的兼容性探测口(CompatibilityDecisionSemantics)
 * 桥接到助手侧权威的决策语义实现。纯委托:解析、校验与 kind 判断
 * 仍然落在原有的 parser/validator 上,这里不做任何额外逻辑。
 */
@Service
public class GlobalAssistantDecisionSemanticsAdapter implements CompatibilityDecisionSemantics {

    private final GlobalAssistantDecisionParser parser;
    private final GlobalAssistantDecisionValidator validator;

    public GlobalAssistantDecisionSemanticsAdapter(GlobalAssistantDecisionParser parser,
                                                   GlobalAssistantDecisionValidator validator) {
        this.parser = parser;
        this.validator = validator;
    }

    @Override
    public Object parseDecision(String content) throws Exception {
        return parser.parse(content);
    }

    @Override
    public void validateDecision(Object decision) throws Exception {
        validator.validate((GlobalAssistantDecision) decision);
    }

    @Override
    public boolean isFinal(Object decision) {
        return ((GlobalAssistantDecision) decision).kind()
                == GlobalAssistantDecision.DecisionKind.FINAL;
    }
}
