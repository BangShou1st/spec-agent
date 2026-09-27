package com.specagent.agent.protocol;

import java.util.List;

/**
 * 文件名:StateUpdateResult.java
 *
 * 用途:Brain 响应中 STATE_UPDATE 调用类型的部分——Brain 提出的
 * 待校验 claim 列表,由 Runtime 校验通过后才持久化。
 */
public record StateUpdateResult(List<ProposedClaim> claims) {

    public StateUpdateResult {
        claims = claims == null ? List.of() : List.copyOf(claims);
    }
}
