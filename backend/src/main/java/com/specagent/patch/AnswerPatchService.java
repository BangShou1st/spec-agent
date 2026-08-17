package com.specagent.patch;

import com.specagent.common.Ids;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists immutable answer patches derived from answers.
 *
 * <p>An answer patch carries domain-neutral {@link Claim}s. Replaying patches
 * along the active route lineage derives the requirement state. Patches are
 * records written by the runtime; they are not produced by a model here.
 */
@Service
public class AnswerPatchService {

    private final AnswerPatchRepository answerPatchRepository;

    public AnswerPatchService(AnswerPatchRepository answerPatchRepository) {
        this.answerPatchRepository = answerPatchRepository;
    }

    public AnswerPatch save(UUID projectId,
                            UUID routeId,
                            UUID sourceNodeId,
                            UUID sourceAnswerId,
                            List<Claim> claims,
                            UUID createdByRunId) {
        UUID patchId = Ids.random();
        Instant now = Instant.now();
        AnswerPatch patch = new AnswerPatch(patchId, projectId, routeId, sourceNodeId,
                sourceAnswerId, claims, createdByRunId, now);
        answerPatchRepository.save(patch);
        return patch;
    }

    public List<AnswerPatch> findByRoute(UUID routeId) {
        return answerPatchRepository.findByRoute(routeId);
    }

    public List<AnswerPatch> findBySourceAnswerIds(List<UUID> answerIds) {
        return answerPatchRepository.findBySourceAnswerIds(answerIds);
    }

    public Optional<AnswerPatch> getPatch(UUID patchId) {
        return answerPatchRepository.findById(patchId);
    }
}
