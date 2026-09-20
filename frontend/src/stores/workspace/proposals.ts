/**
 * Proposals domain: the NodeInspector "Ask AI" query lifecycle and the two
 * durable proposal surfaces (NodeQuery recovery + the confirmable-proposal
 * queue).
 *
 * Every function is the verbatim action body lifted out of `workspaceStore.ts`
 * with `this` replaced by the store instance passed in as the first argument.
 * The store keeps the action names and delegates, so no caller changes.
 */
import { toDisplayError } from '@/api/displayError'
import {
  acceptProposal,
  createNodeQuery,
  getNodeQueryResult,
  rejectProposal,
} from '@/api/graphCommands'
import { listProposals } from '@/api/graphCommands'
import { sleep } from '@/composables/timing'
import type { WorkspaceStore } from '../workspaceStore'
import { NODE_QUERY_TRIGGER } from './shared'

/**
 * Asks AI about a node: enqueues an async query run and polls until the
 * single DECISION call finishes. The query has no graph side effects.
 */
export async function askNodeAIAction(
  store: WorkspaceStore,
  nodeId: string,
  routeId: string | null,
  question: string,
): Promise<boolean> {
  if (!store.projectId || !question.trim()) return false
  if (nodeId.startsWith('pending:')) {
    store.error = {
      code: 'PENDING_NODE_QUERY_NOT_ALLOWED',
      message: '临时运行卡片不是可查询的 canonical Node',
    }
    return false
  }
  // Route semantics: a shared route node (referenced by more than one
  // route) MUST supply an explicit read route as the query context; we
  // must not silently fall back to routeId=null. A floating node (no
  // route membership) is allowed to query with routeId=null.
  if (routeId == null) {
    const membership = store.nodeRouteIds(nodeId)
    if (membership.length > 1) {
      store.error = {
        code: 'SHARED_NODE_REQUIRES_ROUTE',
        message: '共享节点请先选择一条查看路线，再询问 AI',
      }
      return false
    }
  }
  store.error = null
  try {
    const created = await createNodeQuery(store.projectId, nodeId, routeId, question.trim())
    // Capture the immutable query identity once. The poll loop carries
    // this snapshot and must never borrow the routeId/question of a newer
    // query that replaced nodeQuery.
    const querySnapshot = {
      nodeId,
      routeId,
      question: question.trim(),
      runId: created.runId,
    }
    store.nodeQuery = {
      ...querySnapshot,
      status: 'RUNNING',
      message: null,
    }
    await store.pollNodeQuery(querySnapshot)
    return true
  } catch (err) {
    store.error = toDisplayError(err)
    if (store.nodeQuery) store.nodeQuery = { ...store.nodeQuery, status: 'FAILED' }
    return false
  }
}

export async function pollNodeQueryAction(
  store: WorkspaceStore,
  query: {
    runId: string
    nodeId: string
    routeId: string | null
    question: string
  },
): Promise<void> {
  if (!store.projectId) return
  const { runId, nodeId, routeId, question } = query
  const maxAttempts = 40
  for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
    await sleep(1500)
    // Stale poll guard: a newer query may have replaced nodeQuery. If the
    // global nodeQuery no longer belongs to THIS poll run, abandon it so a
    // slow response can never overwrite the latest query's state.
    if (store.nodeQuery && store.nodeQuery.runId !== runId) return
    try {
      const result = await getNodeQueryResult(store.projectId, nodeId, runId)
      // Only the real terminal outcome statuses stop the poll. The result
      // API reports intermediate run lifecycle phases (CONTEXT_BUILT,
      // MODEL_CALLED, ...) verbatim — treating them as terminal would
      // show a spurious query failure whenever a tick lands mid-run.
      const terminal = result.status === 'COMPLETED'
        || result.status === 'FAILED'
        || result.status === 'AWAITING_APPROVAL'
        || result.status === 'ACCEPTED'
        || result.status === 'REJECTED'
        || result.status === 'POLICY_DENIED'
        || result.status === 'NOT_CONFIRMABLE'
      if (!terminal) continue
      if (store.nodeQuery && store.nodeQuery.runId !== runId) return
      store.nodeQuery = {
        nodeId,
        routeId,
        question,
        runId,
        status: result.status === 'FAILED' ? 'FAILED'
          : (result.status as 'RUNNING' | 'COMPLETED' | 'FAILED' | 'AWAITING_APPROVAL' | 'ACCEPTED' | 'REJECTED' | 'POLICY_DENIED' | 'NOT_CONFIRMABLE'),
        message: result.message,
        proposalId: result.proposalId ?? null,
        proposalStatus: result.proposalStatus ?? null,
        actionFamily: result.actionFamily ?? null,
      }
      return
    } catch {
      // Transient poll failures fall through to the next attempt, but a
      // stale poll must still bail out instead of clobbering the latest.
      if (store.nodeQuery && store.nodeQuery.runId !== runId) return
    }
  }
  if (store.nodeQuery && store.nodeQuery.runId === runId) {
    store.nodeQuery = { ...store.nodeQuery, status: 'FAILED' }
    store.error = { code: 'QUERY_TIMEOUT', message: 'AI 查询超时，请稍后重试' }
  }
}

/**
 * Reloads the durable PROPOSED NodeQuery proposals from the backend
 * proposal list API. This is what makes a pending proposal survive a page
 * reload: the list is keyed to each proposal's canonical anchor node
 * (inputNodeId) so the Inspector on that node exposes it even when the
 * in-memory `nodeQuery` was reset or replaced by a newer query.
 */
export async function loadNodeQueryProposalsAction(store: WorkspaceStore): Promise<void> {
  if (!store.projectId) {
    store.nodeQueryProposals = []
    return
  }
  try {
    store.nodeQueryProposals = await listProposals(store.projectId, 'PROPOSED', {
      triggerTypes: [NODE_QUERY_TRIGGER],
    })
  } catch {
    // A failed proposal-list read must not fail the whole workspace load;
    // keep the last known list and let the UI surface loading errors as
    // usual. Pending proposals remain discoverable on the next refresh.
    store.nodeQueryProposals = store.nodeQueryProposals ?? []
  }
}

/**
 * Accepts a pending NodeQuery proposal. The backend returns the confirmed
 * proposal; the canonical graph is refreshed so any produced node/relation
 * becomes visible. The in-memory nodeQuery lifecycle is only mutated when
 * the proposal being accepted IS the current query's own proposal —
 * handling a durable proposal must never mark an unrelated in-memory query
 * as accepted. When the accept reopens an autonomous continuation chain,
 * the origin run is followed to its terminal leaf before the refresh so
 * the UI never settles on an intermediate COMPLETED parent.
 */
export async function acceptNodeQueryProposalAction(
  store: WorkspaceStore,
  proposalId: string,
): Promise<boolean> {
  if (!store.projectId) return false
  store.error = null
  try {
    const accepted = await acceptProposal(proposalId)
    let leafMessage: string | null = null
    if (accepted.originRunId) {
      const leaf = await store.pollRunChainToTerminal(accepted.originRunId)
      if (leaf !== 'unknown' && leaf !== 'failed') {
        leafMessage = leaf.respondMessage ?? null
      }
    }
    await store.refreshWorkspace()
    await store.loadNodeQueryProposals()
    if (store.nodeQuery && store.nodeQuery.proposalId === proposalId) {
      store.nodeQuery = { ...store.nodeQuery, status: 'ACCEPTED', proposalStatus: 'ACCEPTED' }
    }
    store.feedback = leafMessage ?? '已接受提案，Graph 已更新'
    return true
  } catch (err) {
    store.error = toDisplayError(err)
    return false
  }
}

/**
 * 接受一个回答/决策周期的"待确认提案"（意图变更）。接受会在图上执行
 * 该动作并写入 ACCEPT_AGENT_PROPOSAL 不可撤销 barrier——接受前的全部
 * 撤销历史永久封锁，这是设计上的保护而非缺陷。
 */
export async function acceptConfirmableProposalAction(
  store: WorkspaceStore,
  proposalId: string,
): Promise<boolean> {
  if (!store.projectId) return false
  store.error = null
  try {
    await acceptProposal(proposalId)
    await store.refreshWorkspace()
    // 接受会写入不可撤销 barrier（ACCEPT_AGENT_PROPOSAL），撤销可用性
    // 必须重新读取：refreshWorkspace 本身不刷新这组按钮。
    await store.refreshUndoRedoAvailability()
    store.pendingConfirmableProposals = store.pendingConfirmableProposals
      .filter((proposal) => proposal.proposalId !== proposalId)
    store.feedback = '已接受提案，Graph 已更新'
    return true
  } catch (err) {
    store.error = toDisplayError(err)
    return false
  }
}

/** 拒绝一个待确认提案：图保持不变，提案进入 REJECTED 终态。 */
export async function rejectConfirmableProposalAction(
  store: WorkspaceStore,
  proposalId: string,
): Promise<boolean> {
  if (!store.projectId) return false
  store.error = null
  try {
    await rejectProposal(proposalId)
    await store.refreshWorkspace()
    store.pendingConfirmableProposals = store.pendingConfirmableProposals
      .filter((proposal) => proposal.proposalId !== proposalId)
    store.feedback = '已拒绝提案，Graph 保持不变'
    return true
  } catch (err) {
    store.error = toDisplayError(err)
    return false
  }
}

/**
 * Rejects a pending NodeQuery proposal. The graph is left unchanged; only
 * the matching in-memory query (same proposal identity) is marked REJECTED
 * — an unrelated current query B is never touched when rejecting a durable
 * proposal A.
 */
export async function rejectNodeQueryProposalAction(
  store: WorkspaceStore,
  proposalId: string,
): Promise<boolean> {
  if (!store.projectId) return false
  store.error = null
  try {
    await rejectProposal(proposalId)
    await store.loadNodeQueryProposals()
    if (store.nodeQuery && store.nodeQuery.proposalId === proposalId) {
      store.nodeQuery = { ...store.nodeQuery, status: 'REJECTED', proposalStatus: 'REJECTED' }
    }
    store.feedback = '已拒绝提案，Graph 保持不变'
    return true
  } catch (err) {
    store.error = toDisplayError(err)
    return false
  }
}
