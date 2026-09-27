package com.specagent.assistant.conversation;
/**
 * 文件名:GlobalAssistantVersionConflictException.java
 *
 * 用途:线程版本化状态(工作状态/摘要)发生乐观并发冲突时抛出。
 *
 * 角色:conversation 包的类型化冲突异常。调用方重新读取最新状态后
 * 重试是安全的;它区别于"持久化状态已损坏"——后者必须 fail-closed,
 * 不允许重试掩盖。
 */
public class GlobalAssistantVersionConflictException extends IllegalStateException {
    public GlobalAssistantVersionConflictException(String message) {
        super(message);
    }
}
