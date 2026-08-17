package com.specagent.project;

import java.time.Instant;
import java.util.UUID;

/**
 * Requirement exploration workspace.
 *
 * <p>{@code activeRouteId} is the current working focus. It is not the same thing
 * as a route's lifecycle status; there is no {@code active} route status.
 */
public class Project {

    private final UUID id;
    private final String title;
    private final UUID activeRouteId;
    private final UUID defaultProfileId;
    private final Instant createdAt;
    private final Instant updatedAt;

    public Project(UUID id,
                   String title,
                   UUID activeRouteId,
                   UUID defaultProfileId,
                   Instant createdAt,
                   Instant updatedAt) {
        this.id = id;
        this.title = title;
        this.activeRouteId = activeRouteId;
        this.defaultProfileId = defaultProfileId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID id() {
        return id;
    }

    public String title() {
        return title;
    }

    public UUID activeRouteId() {
        return activeRouteId;
    }

    public UUID defaultProfileId() {
        return defaultProfileId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
