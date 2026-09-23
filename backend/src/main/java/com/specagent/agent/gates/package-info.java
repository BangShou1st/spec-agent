/**
 * Reflection and grounding gates over validated model output.
 *
 * <p>Gates are the fail-closed checkpoints between "the model proposed" and
 * "the runtime acts": context freshness ({@code ContextGuard}), patch
 * reflection ({@code PatchReflectionGate}), spec grounding
 * ({@code SpecGroundingGate}) and source-reference integrity
 * ({@code SpecSourceReferenceGuard}). They read runtime facts (including
 * repositories) but never call models or the decision engines.
 */
package com.specagent.agent.gates;
