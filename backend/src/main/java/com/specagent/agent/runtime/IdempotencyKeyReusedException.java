package com.specagent.agent.runtime;

/**
 * 文件名:IdempotencyKeyReusedException.java
 *
 * 用途:项目作用域内的幂等键被复用于另一个不同的逻辑请求时抛出,
 * 防止用旧 key 重放出新请求。
 */
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException() {
        super("The idempotency key was already used for a different request.");
    }
}
