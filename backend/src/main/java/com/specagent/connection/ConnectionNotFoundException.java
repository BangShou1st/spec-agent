package com.specagent.connection;

/**
 * 文件名:ConnectionNotFoundException.java
 *
 * 用途:按产品层 connectionId 管理 Connection 时找不到连接的类型化异常。
 *
 * 映射为 404 CONNECTION_NOT_FOUND,消息稳定且安全。原始 id 与内部细节
 * 绝不泄露;消息只携带调用方提供的产品层 connectionId。
 */
public class ConnectionNotFoundException extends RuntimeException {

    public ConnectionNotFoundException(String connectionId) {
        super("Connection not found: " + connectionId);
    }
}
