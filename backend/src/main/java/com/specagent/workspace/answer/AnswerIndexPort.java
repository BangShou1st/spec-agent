package com.specagent.workspace.answer;

/**
 * 文件名:AnswerIndexPort.java
 *
 * 用途:窄化的出站端口,面向关心 Answer 写入事件的可重建投影(如检索索引)。
 * 回答定稿后通过 {@link #index(Answer)} 通知实现方更新派生数据,使 answer 包
 * 不必依赖具体的索引实现。
 */
public interface AnswerIndexPort {

    void index(Answer answer);
}
