package com.specagent.context;

import com.specagent.patch.AnswerPatch;
import com.specagent.patch.AnswerPatchRepository;
import com.specagent.patch.Claim;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Derives {@link RequirementState} by replaying answer patches.
 *
 * <p>RequirementState is derived, not source of truth. It can be cached, but the
 * immutable lineage, answers, and patches remain authoritative. Replaying the
 * same patches always yields the same state.
 */
@Service
public class RequirementStateBuilder {

    private final AnswerPatchRepository answerPatchRepository;

    public RequirementStateBuilder(AnswerPatchRepository answerPatchRepository) {
        this.answerPatchRepository = answerPatchRepository;
    }

    /**
     * Rebuilds requirement state from an explicit ordered list of patches.
     * Replaying the same patches yields the same state (deterministic, cacheable).
     */
    public RequirementState rebuild(List<AnswerPatch> patches) {
        List<Claim> claims = new ArrayList<>();
        UUID routeId = null;
        for (AnswerPatch patch : patches) {
            if (routeId == null) {
                routeId = patch.routeId();
            }
            claims.addAll(patch.claims());
        }
        return new RequirementState(routeId, claims, Instant.now());
    }

    /**
     * Builds requirement state for a route by loading that route's answer patches
     * in creation order and replaying them.
     */
    public RequirementState buildForRoute(UUID projectId, UUID routeId) {
        List<AnswerPatch> patches = answerPatchRepository.findByRoute(routeId);
        return rebuild(patches);
    }

    /**
     * Builds requirement state from the patches referenced by a context snapshot.
     *
     * <p>Patches are replayed in the explicit order recorded by
     * {@code snapshot.includedPatchIds()}. Order is authoritative: the same
     * patches in different order can yield different requirement state, so the
     * snapshot's patch list is replayed verbatim rather than derived from the
     * answer list.
     */
    public RequirementState buildForContext(ContextSnapshot snapshot) {
        List<AnswerPatch> patches = answerPatchRepository.findByIdsPreservingOrder(snapshot.includedPatchIds());
        return rebuild(patches);
    }
}
