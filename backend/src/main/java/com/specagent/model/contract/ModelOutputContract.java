package com.specagent.model.contract;

import java.util.Map;

/**
 * 文件名:ModelOutputContract.java
 *
 * 用途:一次模型推理调用的、提供商无关的语义化输出契约。调用方声明自己需要的
 * <em>语义形态</em>,提供商适配器负责把该声明翻译成提供商原生的线上字段(例如
 * OpenCode 的 {@code response_format})。这里绝不能出现提供商的线上类型,提供商
 * 专属设置也绝不能泄漏给调用方。适配器遇到不支持的契约变体时按失败处理(fail
 * closed),而不是悄悄降级为纯文本。
 *
 * V2 恰好包含三种变体:{@link Text}(普通文本)、{@link JsonObject}(语法合法的
 * JSON 对象,但不承诺原生 schema 强制)和 {@link JsonSchema}(原生结构化输出)。
 * 未显式指定契约的请求按 {@link Text} 处理,保持历史行为。
 */
public sealed interface ModelOutputContract
        permits ModelOutputContract.Text, ModelOutputContract.JsonObject, ModelOutputContract.JsonSchema {

    /** 普通文本输出;对提供商请求不做任何格式强制。 */
    record Text() implements ModelOutputContract {
    }

    /**
     * 语法合法的 JSON 对象输出,不承诺原生 schema 强制。这是提供商无关的抽象:
     * 不要在这里暴露任何提供商原生的线上类型。
     */
    record JsonObject() implements ModelOutputContract {
    }

    /**
     * 针对一个具名 JSON 对象 schema 的原生结构化输出请求。schema map 只使用纯 JSON
     * 类型(String、Number、Boolean、Map、List、null),保证未来协议版本需要时可以
     * 跨 Java/Python 代理边界序列化。
     */
    record JsonSchema(String name, Map<String, Object> schema) implements ModelOutputContract {

        private static final int MAX_NAME_LENGTH = 64;

        public JsonSchema {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("JSON schema contract name is required");
            }
            String trimmed = name.trim();
            if (trimmed.length() > MAX_NAME_LENGTH || !trimmed.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException(
                        "JSON schema contract name must match [A-Za-z0-9_-]{1,64}");
            }
            name = trimmed;
            if (schema == null || schema.isEmpty()) {
                throw new IllegalArgumentException("JSON schema contract schema is required");
            }
            if (!"object".equals(schema.get("type"))) {
                throw new IllegalArgumentException(
                        "JSON schema contract must describe a top-level JSON object");
            }
            schema = Map.copyOf(schema);
        }
    }

    /** 普通文本输出契约。 */
    static Text text() {
        return new Text();
    }

    /** 不带原生 schema 强制的 JSON 对象输出契约。 */
    static JsonObject jsonObject() {
        return new JsonObject();
    }

    /** 针对一个具名 JSON 对象 schema 的结构化输出契约。 */
    static JsonSchema jsonSchema(String name, Map<String, Object> schema) {
        return new JsonSchema(name, schema);
    }
}
