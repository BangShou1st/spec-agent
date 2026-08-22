package com.specagent.agent.contract;

/**
 * Autonomy policy inputs. Stage A always sends {@code ADVISOR}; the brain can
 * never raise its own authority through this field.
 */
public record AutonomyInputs(String mode) {
}
