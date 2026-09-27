package com.specagent.common;

import java.util.UUID;

/**
 * 文件名:AnswerExistencePort.java
 *
 * 用途:graph 包判断答案状态所需的唯一出口——查询某个规范化节点是否已携带
 * 不可变的 Answer 身份。
 *
 * 答案状态归 answer 包所有,但 graph 的写入期校验器和 undo/redo 前置条件都
 * 需要读它(一个已定稿 Answer 的 Question 既不能再挂第二个 Answer,也不能被
 * 收回)。在这里声明接口,而不是让 {@code GraphInvariantValidator} 和
 * {@code UndoRedoService} 直接依赖 {@code answer.AnswerRepository},可以保证
 * 依赖单向(只有 {@code answer -> graph},因为 {@code AnswerService} 消费的是
 * graph 的校验接缝),从而消除 {@code answer <-> graph} 的包循环。实现位于
 * answer 包内,直接委托给既有的 repository 查询,逻辑不变。
 *
 * 方法签名刻意只用基本类型(传入节点 id、返回 boolean),让这个共享内核
 * 包不引入任何领域类型,也不产生新的依赖。
 */
public interface AnswerExistencePort {

    /** 当规范化节点已存在定稿的不可变 Answer 时返回 true。 */
    boolean nodeHasFinalizedAnswer(UUID nodeId);
}
