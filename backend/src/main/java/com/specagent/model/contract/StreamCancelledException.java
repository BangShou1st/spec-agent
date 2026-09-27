package com.specagent.model.contract;

/**
 * 文件名:StreamCancelledException.java
 *
 * 用途:流式提供商调用的协作式取消信号。当片段监听器拒绝继续接收内容时抛出,
 * 而这只会因为所属 run 已被取消。绝不用于提供商错误、超时或非法输出的场景。
 * 该异常必须原样向上传播(不做包装),让运行时能把 run 终止为 CANCELLED
 * 而不是 FAILED。
 */
public final class StreamCancelledException extends RuntimeException {

    public StreamCancelledException(String message) {
        super(message);
    }
}