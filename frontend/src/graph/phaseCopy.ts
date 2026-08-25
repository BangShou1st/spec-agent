/**
 * Maps real AgentRun phases to user-visible Chinese progress text.
 * These are verifiable runtime phases, never fabricated chain-of-thought.
 */
const PHASE_COPY: Record<string, string> = {
  CREATED: '正在准备…',
  SNAPSHOT_BUILT: '正在准备上下文…',
  STATE_UPDATING: '正在更新需求状态…',
  STATE_UPDATED: '需求状态已更新',
  DECIDING: '正在规划下一步…',
  PROPOSAL_CREATED: '正在执行操作…',
  ARTIFACT_GENERATING: '正在生成产物…',
  EXECUTING: '正在生成…',
  WAITING_USER: '等待用户输入',
  AWAITING_APPROVAL: '等待确认',
  COMPLETED: '已完成',
  FAILED: '执行失败',
  STALE: '已过期',
}

/**
 * Returns the user-visible progress text for a given run phase.
 * Falls back to a generic message for unknown phases.
 */
export function phaseToCopy(phase: string | null | undefined): string {
  if (!phase) return '处理中…'
  return PHASE_COPY[phase] ?? `处理中…（${phase}）`
}

/**
 * Returns true when the run has reached a terminal state.
 */
export function isTerminalPhase(phase: string | null | undefined): boolean {
  return phase === 'COMPLETED' || phase === 'FAILED' || phase === 'STALE'
}
