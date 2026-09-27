package com.specagent.retrieval;

import java.util.Optional;

/**
 * 文件名:EmbeddingGateway.java
 *
 * 用途:可选的向量增强能力的外部服务边界(网关接口),把文本交给
 * 嵌入服务换取向量,供检索索引使用。具体实现由 embedding 子包提供。
 */
public interface EmbeddingGateway {

    Optional<Embedding> embed(String text);

    record Embedding(String model, int dimensions, float[] values) {
    }
}
