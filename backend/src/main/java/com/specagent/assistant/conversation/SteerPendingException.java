package com.specagent.assistant.conversation;

/**
 * 文件名:SteerPendingException.java
 *
 * 用途:仓储层发现线程上已存在未决 Steer 时抛出的重复 Steer 信号,
 * 在 API 边界映射为 HTTP 409。
 *
 * 角色:conversation 包的类型化领域异常,由并发插入触发
 * (数据库部分唯一索引裁决"每线程最多一条未决 Steer")。
 */
public class SteerPendingException extends RuntimeException {
    public SteerPendingException(String message, Throwable cause) {
        super(message, cause);
    }
}
