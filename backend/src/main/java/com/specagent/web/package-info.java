/**
 * 文件名:package-info.java
 *
 * 用途:web/传输边界桥接包说明。存放把供应商/网关失败映射进 API
 * 错误契约的唯一 {@code @RestControllerAdvice}。它位于 {@code com.specagent.api..}
 * 之外,因为 API 边界不得依赖 {@code com.specagent.model..} 包;这个桥是
 * 模型网关异常类型与 API DTO 相遇的唯一位置,只暴露静态、供应商中立的
 * 消息。
 */
package com.specagent.web;