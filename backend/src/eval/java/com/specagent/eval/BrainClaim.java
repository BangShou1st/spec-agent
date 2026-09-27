package com.specagent.eval;

import java.util.List;
import java.util.Map;

/**
 * 文件名:BrainClaim.java
 *
 * 用途:脚本化 Brain 输出(B-fast 层)中的一条 STATE_UPDATE 声明,包含
 * 声明类型、文本种子、状态和置信度。{@code canonical()} 生成规范化字符串,
 * 供分层校验做确定性比对。
 *
 * 协作:作为 {@link BrainScript} 的组成部分,替代生产模型输出。
 */
public record BrainClaim(String kind, String textSeed, String status, Double confidence) {

    public String canonical() {
        return "claim(" + kind + "," + textSeed + "," + status + "," + confidence + ")";
    }
}
