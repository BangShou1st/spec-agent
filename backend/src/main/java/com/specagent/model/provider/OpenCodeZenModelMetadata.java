package com.specagent.model.provider;

import com.sharedlibs.opencodezen.ZenModelMetadata;
import com.sharedlibs.opencodezen.ZenModelProtocol;

/**
 * 文件名:OpenCodeZenModelMetadata.java
 *
 * 用途:官方 OpenCode 模型目录中一条模型的判定事实(仅保留本产品判定所需的字段)。
 *
 * @param modelId  模型 id(与 {@code GET /models} 的 id 对齐)
 * @param free     官方价格元数据中 input/output 均为 0(免费);付费模型永远是 false
 * @param retired  已下线/退役/停用(官方目录的 deprecated / retirement 标记)
 * @param protocol 该模型的原生协议;元数据缺失时为 {@link OpenCodeModelProtocol#UNKNOWN}
 */
public record OpenCodeZenModelMetadata(String modelId, boolean free, boolean retired,
                                       OpenCodeModelProtocol protocol) {

    public OpenCodeZenModelMetadata {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("model id is required");
        }
        protocol = protocol == null ? OpenCodeModelProtocol.UNKNOWN : protocol;
    }

    /** 可供 Spec Agent 的 Chat Completions 调用链使用的模型。 */
    public boolean chatExecutable() {
        return !retired && protocol.chatExecutable();
    }

    /** 免费且可执行:只有这种模型才会出现在"免费模型"选择列表里。 */
    public boolean freeAndChatExecutable() {
        return free && chatExecutable();
    }

    ZenModelMetadata toSharedMetadata() {
        return new ZenModelMetadata(modelId, free, retired, ZenModelProtocol.valueOf(protocol.name()));
    }
}
