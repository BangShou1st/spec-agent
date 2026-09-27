package com.specagent.agent.protocol;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.specagent.common.Json;

/**
 * 文件名:AgentContracts.java
 *
 * 用途:跨语言契约(Java ↔ Python)的严格 JSON 读写入口。
 *
 * 约束:这里的 mapper 刻意比应用默认 mapper 更严格——未知属性、
 * 歧义枚举、给基本类型传 null 全部 fail-closed。请求信封(Java 构建)
 * 和响应信封(Brain 构建、不可信)都必须经过它,保证 golden fixtures
 * 在边界两侧行为一致。
 */
public final class AgentContracts {

    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
            .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, false)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final Json JSON = new Json(STRICT_MAPPER);

    private AgentContracts() {
    }

    /** 按带版本的线上(wire)字段规则序列化契约值。 */
    public static String write(Object value) {
        return JSON.write(value);
    }

    /**
     * 以 fail-closed 方式解析契约值。任何未知字段、未知枚举或形状违约
     * 都会抛出 {@link AgentContractException}。
     */
    public static <T> T read(String json, Class<T> type) {
        try {
            return JSON.read(json, type);
        } catch (IllegalStateException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof com.fasterxml.jackson.databind.DatabindException databindEx) {
                throw new AgentContractException(
                        "Contract violation in " + type.getSimpleName() + ": "
                                + databindEx.getOriginalMessage());
            }
            throw new AgentContractException(
                    "Contract violation in " + type.getSimpleName() + ": " + ex.getMessage());
        }
    }
}
