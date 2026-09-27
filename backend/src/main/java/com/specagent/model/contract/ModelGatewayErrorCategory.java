package com.specagent.model.contract;

/**
 * 文件名:ModelGatewayErrorCategory.java
 *
 * 用途:{@link ModelGateway} 契约的提供商无关错误分类。各网关实现把自己的提供商
 * 专属错误映射到这套词汇上,让上层推理逻辑无需了解具体提供商就能诊断失败原因。
 * 不要在这里加入任何提供商平台特有的概念。
 */
public enum ModelGatewayErrorCategory {
    /** HTTP 请求超时。 */
    TIMEOUT,
    /** 连接建立失败或中途被断开。 */
    CONNECTION,
    /** 提供商拒绝了凭据(HTTP 401 / 403)。 */
    AUTHENTICATION,
    /** 提供商对请求限流(HTTP 429)。 */
    RATE_LIMITED,
    /** 提供商返回服务端错误(HTTP 5xx)。 */
    SERVER_ERROR,
    /** 提供商返回了意料之外的 4xx 响应。 */
    PROVIDER_REQUEST_ERROR,
    /** 响应体格式非法,或与预期的结构不符。 */
    INVALID_RESPONSE,
    /** 补全调用成功但没有产出可用内容。 */
    EMPTY_CONTENT,
    /** 网关无法运行:配置的模型不在允许范围内。 */
    INVALID_MODEL,
    /** 网关无法运行:缺少必需的配置项。 */
    NOT_CONFIGURED
}