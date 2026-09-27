package com.specagent.agent.protocol;

import java.util.List;
import java.util.UUID;

/**
 * 文件名:PatchView.java
 *
 * 用途:决策引擎视角下的一条已持久化的回答补丁(patch),
 * 由该补丁沉淀出的 claim 列表组成。
 */
public record PatchView(UUID id, List<ClaimView> claims) {

    public PatchView {
        claims = claims == null ? List.of() : List.copyOf(claims);
    }
}
