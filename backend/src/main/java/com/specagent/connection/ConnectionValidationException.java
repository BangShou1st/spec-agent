package com.specagent.connection;

/**
 * 文件名:ConnectionValidationException.java
 *
 * 用途:Connection 管理输入校验失败时抛出的类型化异常,
 * 映射为 400 VALIDATION_ERROR,消息稳定且安全。
 */
public class ConnectionValidationException extends RuntimeException {

    public ConnectionValidationException(String message) {
        super(message);
    }
}
