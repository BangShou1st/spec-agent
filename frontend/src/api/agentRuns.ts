/**
 * AgentRun async command + polling API.
 *
 * The frontend never hand-builds run payloads elsewhere: answer/repair (and
 * later question/spec/replacement) mutations go through these explicit
 * methods, mirroring the backend contract exactly. The HTTP command returns
 * immediately with a runId; completion is observed by polling the run read
 * endpoint until a terminal status. No WebSocket/SSE.
 */
import { apiClient } from './client'

export type AgentRunOperation = 'ANSWER_TIP' | 'RESUME_ANSWER'

/** Coarse lifecycle statuses returned by GET /agent-runs/{runId}. */
export type AgentRunStatus =
  | 'created'
  | 'running'
  | 'completed'
  | 'failed'

/** Real persisted run phases (AgentRunPhase); progress copy derives from these. */
export const AGENT_RUN_PHASES = [
  'CREATED',
  'SNAPSHOT_BUILT',
  'STATE_UPDATING',
  'STATE_UPDATED',
  'DECIDING',
  'PROPOSAL_CREATED',
  'AWAITING_APPROVAL',
  'EXECUTING',
  'WAITING_USER',
  'COMPLETED',
  'FAILED',
  'STALE',
] as const

export type AgentRunPhase = (typeof AGENT_RUN_PHASES)[number]

const TERMINAL_STATUSES: ReadonlySet<string> = new Set(['completed', 'failed'])

/** Poll cadence: bounded and modest — no tight infinite loop. */
export const AGENT_RUN_POLL_INTERVAL_MS = 1500

/** Upper bound on polls per run; exceeded means FAILED for recovery UX. */
export const AGENT_RUN_MAX_POLLS = 120

export interface AgentRunCreated {
  runId: string
  operation: string
  phase: string
}

export interface AgentRunView {
  runId: string
  projectId: string
  routeId: string
  operation: string
  status: AgentRunStatus
  phase: AgentRunPhase | string
  producedNodeId: string | null
  producedAnswerId: string | null
  producedPatchId: string | null
  producedSpecSnapshotId: string | null
}

export interface CreateAgentRunPayload {
  operation: AgentRunOperation
  /** Node being answered; backend falls back to the active route tip when omitted. */
  nodeId?: string | null
  selectedOptionId?: string | null
  freeText?: string | null
  /** Required for RESUME_ANSWER: the persisted Answer id being resumed. */
  answerId?: string | null
}

export function createAgentRun(
  projectId: string,
  payload: CreateAgentRunPayload,
): Promise<AgentRunCreated> {
  return apiClient.post<AgentRunCreated>(`/projects/${projectId}/agent-runs`, {
    operation: payload.operation,
    nodeId: payload.nodeId ?? null,
    selectedOptionId: payload.selectedOptionId ?? null,
    freeText: payload.freeText ?? null,
    answerId: payload.answerId ?? null,
  })
}

export function getAgentRun(projectId: string, runId: string): Promise<AgentRunView> {
  return apiClient.get<AgentRunView>(`/projects/${projectId}/agent-runs/${runId}`)
}

export function isTerminalRunStatus(status: string): boolean {
  return TERMINAL_STATUSES.has(status)
}
