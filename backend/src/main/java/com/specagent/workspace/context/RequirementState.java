package com.specagent.workspace.context;

import com.specagent.workspace.patch.Claim;
import com.specagent.workspace.patch.ClaimStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 文件名:RequirementState.java
 *
 * 用途:为 route tip 派生的需求状态,由沿活跃 route 世系回放回答补丁
 * (answer patch)得到。它可以被缓存,但永远不是事实源——不可变的世系、回答
 * 与补丁才是。下游规格生成据此判断哪些需求已确认、哪些仍未解决。
 */
public class RequirementState {

    private final List<Claim> claims;
    private final Instant builtAt;
    private final UUID routeId;

    public RequirementState(UUID routeId, List<Claim> claims, Instant builtAt) {
        this.routeId = routeId;
        this.claims = claims == null ? List.of() : List.copyOf(claims);
        this.builtAt = builtAt;
    }

    public UUID routeId() {
        return routeId;
    }

    public List<Claim> claims() {
        return claims;
    }

    public Instant builtAt() {
        return builtAt;
    }

    public boolean isEmpty() {
        return claims.isEmpty();
    }

    public List<Claim> confirmed() {
        return claims.stream().filter(Claim::isConfirmed).toList();
    }

    public List<Claim> unresolved() {
        return claims.stream()
                .filter(c -> c.status() == ClaimStatus.UNRESOLVED || c.status() == ClaimStatus.ASSUMED)
                .toList();
    }
}
