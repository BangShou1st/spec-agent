/**
 * Maps real AgentRun phases to user-visible Chinese progress text.
 * These are verifiable runtime phases, never fabricated chain-of-thought.
 *
 * Copy lives in presentation/agentPresentation.ts; this module keeps its
 * exported API for existing callers and delegates to the stable mapping.
 */
import { agentPhaseLabel } from '@/presentation/agentPresentation'

/**
 * Returns the user-visible progress text for a given run phase.
 * Falls back to a generic message for unknown phases (never leaks raw phase).
 */
export function phaseToCopy(phase: string | null | undefined): string {
  return agentPhaseLabel(phase)
}

/**
 * Returns true when the run has reached a terminal state.
 */
export function isTerminalPhase(phase: string | null | undefined): boolean {
  return phase === 'COMPLETED' || phase === 'FAILED' || phase === 'STALE'
}
