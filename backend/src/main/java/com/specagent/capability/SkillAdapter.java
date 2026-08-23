package com.specagent.capability;

/**
 * Marker for Skill-package adapters. A Skill is a reusable capability
 * package (instructions, schemas, references, underlying tool calls) — not
 * an agent personality. No Skill adapter is wired in this stage; the
 * boundary exists so Skill internals can evolve without changing the action
 * protocol.
 */
public interface SkillAdapter extends CapabilityAdapter {
}
