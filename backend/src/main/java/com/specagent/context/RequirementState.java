package com.specagent.context;

import com.specagent.patch.Claim;
import com.specagent.patch.ClaimStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Derived requirement state for a route tip.
 *
 * <p>RequirementState is derived by replaying answer patches along the active
 * route lineage. It can be cached, but it is never the source of truth; the
 * immutable lineage, answers, and patches are.
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
