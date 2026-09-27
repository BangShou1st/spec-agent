package com.specagent.common;

import java.util.UUID;

/**
 * 文件名:SharedQuestionStatePort.java
 *
 * 用途:answer 包在定稿 Answer 之前,请 graph 包校验规范化 Question 节点
 * 项目级共享状态的出口接口。
 *
 * {@code SHARED_STATE_DIVERGENCE} 规则是一条图不变式:一个规范化 Question
 * 在全项目只携带一个不可变的 Answer 身份,所以在同一节点上定稿第二个 Answer
 * 必须失败。规则的所有者是 {@code graph.GraphInvariantValidator},但触发它的
 * 写入方是 {@code answer.AnswerService}。在这里声明接口,可以让依赖保持单向
 * (只有 {@code graph -> answer},用于读 answer 是否存在),从而消除
 * {@code answer <-> graph} 的包循环。实现位于 graph 包,直接委托给既有的
 * 校验方法——规则逻辑没有任何移动。
 *
 * 方法签名刻意只用基本类型(项目 id + 节点 id),让这个共享内核包不引入
 * 任何领域类型,也不产生新的依赖。
 */
public interface SharedQuestionStatePort {

    /**
     * 当规范化节点已携带不可变的 Answer 身份时校验失败(收敛于抛异常),
     * 调用方不得再持久化第二个 Answer。
     *
     * @throws IllegalStateException 携带 {@code SHARED_STATE_DIVERGENCE} 错误码
     */
    void validateSharedQuestionState(UUID projectId, UUID nodeId);
}
