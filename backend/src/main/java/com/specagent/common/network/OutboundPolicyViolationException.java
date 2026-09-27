package com.specagent.common.network;

/**
 * 文件名:OutboundPolicyViolationException.java
 *
 * 用途:出网 URL/主机违反共享网络策略时抛出的类型化异常,
 * 不向调用方暴露供应商或堆栈细节。
 */
public class OutboundPolicyViolationException extends RuntimeException {

    public OutboundPolicyViolationException(String message) {
        super(message);
    }
}