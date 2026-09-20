/**
 * Shared, store-agnostic helpers for the workspace domain modules.
 *
 * These used to live at the top of `workspaceStore.ts`. They are pure functions
 * / constants with no store access, so they belong next to the domain modules
 * rather than inside the store definition.
 */
import type { DisplayError } from '@/api/displayError'
import { listProposals } from '@/api/graphCommands'
import type { ProjectProposalSummary } from '@/api/graphCommands'

/**
 * The trigger type that marks a proposal as contextual "Ask AI" output of the
 * NodeInspector. The proposal list API is shared by every run type, and recovery
 * must never infer the query origin from inputNodeId (every run type carries
 * one), so this explicit AgentRunTriggerType code is the only safe
 * discriminator.
 */
export const NODE_QUERY_TRIGGER = 'node_query'

/**
 * Reads the durable PROPOSED NodeQuery proposals for one project, fail-soft:
 * a failed proposal-list read must never fail the workspace load or refresh
 * (pending proposals remain discoverable on the next successful load). The
 * pure-function shape lets loadWorkspace/refreshWorkspace fetch it in the same
 * Promise.all as the canonical reads, so the workspace critical path and its
 * load/layout timing are unchanged.
 *
 * The narrowing is applied by the backend (see ProposalTriggerFilter) instead of
 * by post-filtering a full list here: the client no longer downloads proposals
 * it would discard, and "which run types belong to which surface" is decided
 * next to the data.
 */
export function loadNodeQueryProposalsSafely(projectId: string): Promise<ProjectProposalSummary[]> {
  return listProposals(projectId, 'PROPOSED', { triggerTypes: [NODE_QUERY_TRIGGER] })
    .catch(() => [])
}

/**
 * 回答/决策周期产生的"待确认提案"（CREATE_NODE / CONNECT_NODE 等意图变更，
 * 策略层要求用户显式确认后才执行）。与 node_query 提案互斥：后者由
 * Inspector 的问 AI 流程消费，这里的进入全局确认区。互补关系由后端的
 * excludeTriggerType 表达，而不是本地过滤。
 */
export function loadConfirmableProposalsSafely(projectId: string): Promise<ProjectProposalSummary[]> {
  return listProposals(projectId, 'PROPOSED', {
    excludeTriggerTypes: [NODE_QUERY_TRIGGER],
  }).catch(() => [])
}

/**
 * 冲突类错误码：请求与运行时状态相撞（路线被接替/指针悬空/目标过期）。
 * 这类错误的共同解法是"去操作当前最新的可回答问题节点"，因此展示层统一
 * 追加该节点的可读指引，而不是让用户对着一句抽象的冲突描述猜。
 */
const CONFLICT_HINT_CODES = new Set([
  'RUNTIME_CONFLICT',
  'ROUTE_NOT_OPEN',
  'ROUTE_NOT_ACTIVATABLE',
  'AGENT_RUN_TARGET_STALE',
])

/**
 * Appends the current answerable question's title to a conflict-family error
 * so the banner names the node the user should operate on. Best effort: the
 * hint comes from the last successful workspace read and is skipped entirely
 * when that read has no answerable node — a stale hint is worse than none.
 */
export function withAnswerableNodeHint(
  error: DisplayError,
  answerableQuestion: string | null,
): DisplayError {
  if (!CONFLICT_HINT_CODES.has(error.code) || !answerableQuestion) return error
  const trimmed = answerableQuestion.trim()
  if (!trimmed) return error
  return {
    ...error,
    message: `${error.message}。当前等待回答的问题是「${trimmed.slice(0, 40)}」，可直接在该节点上作答`,
  }
}
