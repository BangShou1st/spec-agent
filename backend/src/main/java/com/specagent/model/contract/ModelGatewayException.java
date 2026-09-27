package com.specagent.model.contract;

/**
 * 文件名:ModelGatewayException.java
 *
 * 用途:{@link ModelGateway} 实现抛出的提供商无关异常。上层推理逻辑捕获该类型后
 * 通过 {@link #gatewayCategory()} 读取错误分类来诊断问题,绝不能依赖某个具体提供商
 * 的异常类型。异常消息中永不包含 API key 或 Authorization 头的值,agent 轨迹也
 * 永不持久化该消息。
 */
public class ModelGatewayException extends RuntimeException {

    private final ModelGatewayErrorCategory gatewayCategory;
    private final Integer httpStatus;

    public ModelGatewayException(ModelGatewayErrorCategory category, String message) {
        this(category, message, null, null);
    }

    public ModelGatewayException(ModelGatewayErrorCategory category, String message, Integer httpStatus) {
        this(category, message, httpStatus, null);
    }

    public ModelGatewayException(ModelGatewayErrorCategory category, String message, Throwable cause) {
        this(category, message, null, cause);
    }

    protected ModelGatewayException(ModelGatewayErrorCategory category,
                                    String message,
                                    Integer httpStatus,
                                    Throwable cause) {
        super(message, cause);
        this.gatewayCategory = category;
        this.httpStatus = httpStatus;
    }

    public ModelGatewayErrorCategory gatewayCategory() {
        return gatewayCategory;
    }

    /**
     * 导致失败的 HTTP 状态码;失败未到达 HTTP 层时(超时、连接、响应非法)返回 null。
     */
    public Integer httpStatus() {
        return httpStatus;
    }
}