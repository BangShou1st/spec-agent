package com.specagent.readmodel.requirement;

import com.specagent.context.RequirementState;
import com.specagent.patch.Claim;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Read-only requirement-state view grouped by the actual runtime claim status.
 *
 * <p>Grouping follows {@code ClaimStatus} exactly (confirmed, assumed,
 * unresolved, rejected) and is derived on the backend; a client never infers a
 * status. {@code routeId} is {@code null} when the project has no active route
 * and the view is a safe empty read model instead of an invented route.
 */
public record RequirementStateView(
        UUID projectId,
        UUID routeId,
        List<RequirementClaimView> confirmed,
        List<RequirementClaimView> assumed,
        List<RequirementClaimView> unresolved,
        List<RequirementClaimView> rejected,
        Instant builtAt) {

    /** Safe empty read model for a project without an active route. */
    public static RequirementStateView empty(UUID projectId) {
        return new RequirementStateView(projectId, null,
                List.of(), List.of(), List.of(), List.of(), Instant.now());
    }

    public static RequirementStateView from(UUID projectId, UUID routeId, RequirementState state) {
        List<RequirementClaimView> confirmed = new ArrayList<>();
        List<RequirementClaimView> assumed = new ArrayList<>();
        List<RequirementClaimView> unresolved = new ArrayList<>();
        List<RequirementClaimView> rejected = new ArrayList<>();
        for (Claim claim : state.claims()) {
            RequirementClaimView view = RequirementClaimView.from(claim);
            switch (claim.status()) {
                case CONFIRMED -> confirmed.add(view);
                case ASSUMED -> assumed.add(view);
                case UNRESOLVED -> unresolved.add(view);
                case REJECTED -> rejected.add(view);
            }
        }
        return new RequirementStateView(projectId, routeId,
                List.copyOf(confirmed),
                List.copyOf(assumed),
                List.copyOf(unresolved),
                List.copyOf(rejected),
                state.builtAt());
    }
}