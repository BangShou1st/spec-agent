package com.specagent.agent.protocol;

import java.util.UUID;

/**
 * 文件名:AnswerView.java
 *
 * 用途:决策引擎视角下的一条不可变用户回答——用户在某个节点上
 * 选择的选项或填写的自由文本,作为决策周期的输入事实之一。
 */
public record AnswerView(UUID id,
                         UUID nodeId,
                         UUID selectedOptionId,
                         String freeText) {
}
