package com.specagent.connection.credentials;

import java.util.UUID;

/**
 * 文件名:SecretStore.java
 *
 * 用途:Connection 领域的窄密钥存储边界。Connection 行只持久化一个
 * {@code credentialRef};明文密钥藏在这个接口之后,静态加密存储,绝不写入
 * trace、模型上下文、capability 结果、描述符或错误响应。
 */
public interface SecretStore {

    /**
     * 为某个连接行存储密钥,生成新的运行时持有引用并返回该引用。
     * 最多只保留掩码后缀用于状态展示。
     */
    String store(UUID connectionRowId, String secret);

    /**
     * 按凭据引用取出授权调用方的密钥。实现必须保证无法通过模型/API 面
     * 枚举或外带密钥。
     */
    String resolve(String credentialRef);

    /** 掩码后的展示后缀;引用不存在时返回 null。 */
    String maskedSuffix(String credentialRef);

    void delete(String credentialRef);
}