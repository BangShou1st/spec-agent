package com.specagent.eval;

import java.util.Map;

/**
 * 文件名:PropertyCheck.java
 *
 * 用途:{@code expect} 块中的一条必填属性校验声明:属性名加参数表。
 * {@code canonical()} 生成场景哈希用的规范化字符串。
 *
 * 协作:由 {@link ExpectSpec} 持有,由 {@link LayerBFast} 逐条求值为
 * {@link CheckResult}。
 */
public record PropertyCheck(String property, Map<String, Object> args) {

    public PropertyCheck {
        args = args == null ? Map.of() : Map.copyOf(args);
    }

    public String canonical() {
        return "prop(" + property + "," + new java.util.TreeMap<>(args) + ")";
    }
}
