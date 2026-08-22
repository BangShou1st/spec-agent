package com.specagent.agent.contract;

/**
 * Low-authority workspace metadata. {@code projectTitle} is display context
 * only and must never be promoted to objective, requirement, or scope by the
 * decision engine.
 */
public record SnapshotMetadata(String projectTitle) {
}
