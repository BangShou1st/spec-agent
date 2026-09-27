package com.specagent.model.provider;

/**
 * 文件名:OpenCodeModelErrorCategory.java
 *
 * 用途:OpenCode Zen 请求的可诊断失败分类。
 */
public enum OpenCodeModelErrorCategory {
    /** HTTP 请求超时。 */
    TIMEOUT,
    /** 连接建立失败或中途被断开。 */
    CONNECTION,
    /** OpenCode 拒绝了凭据(HTTP 401 / 403)。 */
    AUTHENTICATION,
    /** OpenCode 对请求限流(HTTP 429)。 */
    RATE_LIMITED,
    /** OpenCode 返回服务端错误(HTTP 5xx)。 */
    SERVER_ERROR,
    /** OpenCode 返回了意料之外的 4xx 响应。 */
    PROVIDER_REQUEST_ERROR,
    /** 响应体格式非法,或与预期的结构不符。 */
    INVALID_RESPONSE,
    /** 补全调用成功但没有产出可用内容。 */
    EMPTY_CONTENT,
    /** 当前生效的设置来源不允许使用所配置的模型。 */
    INVALID_MODEL,
    /** 网关无法运行:缺少必需的配置项。 */
    NOT_CONFIGURED
}
