package com.specagent.common;

/**
 * 文件名:ApiFieldError.java
 *
 * 用途:{@link ApiErrorResponse} 中的字段级校验明细。
 *
 * 只暴露字段名和静态的校验原因,被拒绝的原始值绝不回显给调用方。
 */
public record ApiFieldError(String field, String message) {
}
