package com.specagent.agent.ranking;

/**
 * 文件名:SemanticRankingException.java
 *
 * 用途:排序契约违反或胜出动作选择失败时抛出的异常。
 * 遵循 fail-closed 原则:排序协议不合法、无法选出获胜动作时直接报错终止,
 * 而不是带病继续推进。
 *
 * 协作:由 SemanticRankingSelector 等排序链路在契约校验失败时抛出。
 */
public class SemanticRankingException extends IllegalArgumentException {

    public SemanticRankingException(String message) {
        super(message);
    }
}
