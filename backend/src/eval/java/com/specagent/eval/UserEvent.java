package com.specagent.eval;

import com.specagent.agent.protocol.AgentEvent;

/**
 * 文件名:UserEvent.java
 *
 * 用途:触发场景生产回答周期的用户事件(密封接口)。文本均由种子确定性
 * 渲染,而非固定句子。{@code canonical()} 生成场景哈希用的规范化字符串。
 *
 * 协作:由 {@link GivenSpec} 持有,由 {@link ScenarioRunner} 驱动执行。
 */
public sealed interface UserEvent
        permits UserEvent.AnswerTip, UserEvent.DivergentAnswer {

    /** 用种子推导的自由文本回答活动路由末端。 */
    record AnswerTip(String freeTextSeed,
                     AgentEvent.PersistenceIntent persistenceIntent) implements UserEvent {
        public AnswerTip(String freeTextSeed) {
            this(freeTextSeed, null);
        }
    }

    /**
     * 从分叉路由对一个已有定稿 Answer 的规范节点尝试第二次回答
     * (共享状态发散探针;必须 fail-closed)。
     */
    record DivergentAnswer(String targetStepRef, String freeTextSeed) implements UserEvent {
    }

    /** 场景哈希用的规范化渲染。 */
    static String canonical(UserEvent event) {
        if (event instanceof AnswerTip answer) {
            return "answer-tip(" + answer.freeTextSeed() + ";intent="
                    + answer.persistenceIntent() + ")";
        }
        if (event instanceof DivergentAnswer divergent) {
            return "divergent(" + divergent.targetStepRef() + "," + divergent.freeTextSeed() + ")";
        }
        return "unknown";
    }
}
