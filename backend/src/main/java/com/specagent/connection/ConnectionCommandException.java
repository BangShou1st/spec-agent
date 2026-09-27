package com.specagent.connection;

/**
 * 文件名:ConnectionCommandException.java
 *
 * 用途:Connection 生命周期操作(创建/测试/连接/启用/删除/刷新)失败时
 * 抛出的类型化异常,原始供应商报错与堆栈细节不会到达调用方。
 */
public class ConnectionCommandException extends RuntimeException {

    public ConnectionCommandException(String message) {
        super(message);
    }
}