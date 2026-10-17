package com.specagent.eval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Tracks only projects announced by the ScenarioRunner creation boundary. */
final class EvalProjectCleanup {

    private final LinkedHashSet<UUID> projectIds = new LinkedHashSet<>();

    void register(UUID projectId) {
        if (projectId != null) {
            projectIds.add(projectId);
        }
    }

    void cleanupAll(Consumer<UUID> cleanProject, Throwable primaryFailure) {
        List<UUID> targets = new ArrayList<>(projectIds);
        projectIds.clear();

        RuntimeException cleanupFailure = null;
        for (UUID projectId : targets) {
            try {
                cleanProject.accept(projectId);
            } catch (RuntimeException failure) {
                if (cleanupFailure == null) {
                    cleanupFailure = new IllegalStateException(
                            "Failed to clean up one or more evaluation projects",
                            failure);
                } else {
                    cleanupFailure.addSuppressed(failure);
                }
            }
        }

        if (cleanupFailure == null) {
            return;
        }
        if (primaryFailure != null) {
            primaryFailure.addSuppressed(cleanupFailure);
            return;
        }
        throw cleanupFailure;
    }
}
