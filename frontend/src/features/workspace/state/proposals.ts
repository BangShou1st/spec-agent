// 文件名:proposals.ts
// 用途:提案领域逻辑:NodeInspector "问 AI" 的查询生命周期(发起/轮询/恢复)与两类持久化提案(可确认提案队列 + NodeQuery 提案)的接受/拒绝,从 workspaceStore 拆出。
/*
 * 提案领域:NodeInspector "Ask AI" 查询生命周期,以及两个持久化的提案
 * 表面(NodeQuery 恢复 + 可确认提案队列)。
 *
 * 每个函数都是从 `workspaceStore.ts` 原样搬出的 action 主体,只是把
 * `this` 换成了作为第一个参数传入的 store 实例。store 保留原 action 名
 * 并委托到这里,调用方零改动。
 */
import { toDisplayError } from '@/shared/http/displayError'
import {
  acceptProposal,
  createNodeQuery,
  getNodeQueryResult,
  rejectProposal,
} from '@/features/workspace/api/graphCommands'
import { listProposals } from '@/features/workspace/api/graphCommands'
import { sleep } from '@/shared/lib/timing'
import type { ProposalSlice } from './slices'
import { NODE_QUERY_TRIGGER } from './shared'

/*
 * 就某节点询问 AI:排入一个异步查询 run,并轮询直到单次 DECISION 调用
 * 结束。该查询对图没有副作用。
 */
export async function askNodeAIAction(
  store: ProposalSlice,
  nodeId: string,
  routeId: string | null,
  question: string,
): Promise<boolean> {
  if (!store.projectId || !question.trim()) return false
  const projectId = store.projectId
  const session = store.projectSessionId
  const isCurrent = (): boolean =>
    store.projectSessionId === session && store.projectId === projectId
  if (nodeId.startsWith('pending:')) {
    store.error = {
      code: 'PENDING_NODE_QUERY_NOT_ALLOWED',
      message: '临时运行卡片不是可查询的 canonical Node',
    }
    return false
  }
  // 路线语义:被多于一条路线引用的共享路线节点必须提供显式阅读路线作为
  // 查询上下文;绝不能静默回退到 routeId=null。浮动节点(无路线归属)
  // 允许以 routeId=null 查询。
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
    const created = await createNodeQuery(projectId, nodeId, routeId, question.trim())
    if (!isCurrent()) return false
    // 一次性捕获不可变的查询身份。轮询循环携带这份快照,绝不借用取代了
    // nodeQuery 的更新查询的 routeId/question。
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
    if (!isCurrent()) return false
    store.error = toDisplayError(err)
    if (store.nodeQuery) store.nodeQuery = { ...store.nodeQuery, status: 'FAILED' }
    return false
  }
}

export async function pollNodeQueryAction(
  store: ProposalSlice,
  query: {
    runId: string
    nodeId: string
    routeId: string | null
    question: string
  },
): Promise<void> {
  if (!store.projectId) return
  const projectId = store.projectId
  const session = store.projectSessionId
  const isCurrent = (): boolean =>
    store.projectSessionId === session && store.projectId === projectId
  const { runId, nodeId, routeId, question } = query
  const maxAttempts = 40
  for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
    await sleep(1500)
    // 过期轮询守卫:更新的查询可能已取代 nodeQuery,且切换项目会让整次
    // 轮询失效——两个检查都在每次 await 之后执行,绝不只放在下一轮之前。
    if (!isCurrent()) return
    if (store.nodeQuery && store.nodeQuery.runId !== runId) return
    try {
      const result = await getNodeQueryResult(projectId, nodeId, runId)
      if (!isCurrent()) return
      // 只有真正的终态才停止轮询。结果 API 会原样报告中间的 run 生命周期
      // 阶段(CONTEXT_BUILT、MODEL_CALLED……)——把它们当终态会在 tick
      // 恰好落在 run 中途时显示虚假的查询失败。
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
      // 瞬时轮询失败落到下一次尝试;但过期轮询仍必须退出,而不是覆盖
      // 最新的状态。
      if (!isCurrent()) return
      if (store.nodeQuery && store.nodeQuery.runId !== runId) return
    }
  }
  if (isCurrent() && store.nodeQuery && store.nodeQuery.runId === runId) {
    store.nodeQuery = { ...store.nodeQuery, status: 'FAILED' }
    store.error = { code: 'QUERY_TIMEOUT', message: 'AI 查询超时，请稍后重试' }
  }
}

/*
 * 节点查询重试成功后的结果刷新(6-5):重试 run 与原失败 run 是同一个
 * 查询任务的新尝试;成功后把检查器的查询视图更新为新 run 的真实结果,
 * 绝不停留在旧失败状态。仅在检查器当前正展示该失败任务的结果时生效。
 */
export async function refreshNodeQueryResultAction(
  store: ProposalSlice,
  nodeId: string,
  runId: string,
  fallback: { routeId: string | null; question: string },
): Promise<void> {
  if (!store.projectId) return
  try {
    const result = await getNodeQueryResult(store.projectId, nodeId, runId)
    const terminal = result.status === 'COMPLETED'
      || result.status === 'AWAITING_APPROVAL'
      || result.status === 'ACCEPTED'
      || result.status === 'REJECTED'
      || result.status === 'POLICY_DENIED'
      || result.status === 'NOT_CONFIRMABLE'
    if (!terminal) return
    // 检查器仍指向旧失败 run 时才刷新;更晚的查询已取代该视图。
    if (store.nodeQuery && store.nodeQuery.runId !== runId) return
    store.nodeQuery = {
      nodeId,
      routeId: fallback.routeId,
      question: fallback.question,
      runId,
      status: result.status as 'COMPLETED' | 'AWAITING_APPROVAL' | 'ACCEPTED' | 'REJECTED' | 'POLICY_DENIED' | 'NOT_CONFIRMABLE',
      message: result.message,
      proposalId: result.proposalId ?? null,
      proposalStatus: result.proposalStatus ?? null,
      actionFamily: result.actionFamily ?? null,
    }
  } catch {
    // 结果读取失败保持原状态;失败清单对账仍是权威。
  }
}

/*
 * 从后端提案列表 API 重新加载持久的 PROPOSED NodeQuery 提案。这让待确认
 * 提案能在页面刷新后存活:列表以每条提案的 canonical 锚节点(inputNodeId)
 * 为键,因此即使内存中的 nodeQuery 已被重置或被更新的查询取代,该节点的
 * Inspector 仍能看到它。
 */
export async function loadNodeQueryProposalsAction(store: ProposalSlice): Promise<void> {
  const projectId = store.projectId
  if (!projectId) {
    store.nodeQueryProposals = []
    return
  }
  const session = store.projectSessionId
  try {
    const proposals = await listProposals(projectId, 'PROPOSED', {
      triggerTypes: [NODE_QUERY_TRIGGER],
    })
    // 过期的提案读取绝不能覆盖新纪元的列表。
    if (store.projectSessionId !== session || store.projectId !== projectId) return
    store.nodeQueryProposals = proposals
  } catch {
    // 提案列表读取失败绝不能让整个工作区加载失败;保留上次已知列表,让
    // UI 照常展示加载错误。待确认提案会在下一次刷新时重新可见。
    if (store.projectSessionId !== session || store.projectId !== projectId) return
    store.nodeQueryProposals = store.nodeQueryProposals ?? []
  }
}

/*
 * 接受一个待确认的 NodeQuery 提案。后端返回已确认提案;随后刷新 canonical
 * 图,让产出的节点/关系可见。只有被接受的提案就是当前查询自己的提案时,
 * 才修改内存中的 nodeQuery 生命周期——处理一条持久化提案绝不能把另一条
 * 无关的内存查询标记为已接受。当接受会重新打开一条自主续问链时,先追踪
 * 起源 run 到终态叶子再刷新,UI 才不会停在中间的 COMPLETED 父节点上。
 */
export async function acceptNodeQueryProposalAction(
  store: ProposalSlice,
  proposalId: string,
): Promise<boolean> {
  if (!store.projectId) return false
  const projectId = store.projectId
  const session = store.projectSessionId
  const isCurrent = (): boolean =>
    store.projectSessionId === session && store.projectId === projectId
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
    if (!isCurrent()) return false
    if (store.nodeQuery && store.nodeQuery.proposalId === proposalId) {
      store.nodeQuery = { ...store.nodeQuery, status: 'ACCEPTED', proposalStatus: 'ACCEPTED' }
    }
    store.feedback = leafMessage ?? '已接受提案，Graph 已更新'
    return true
  } catch (err) {
    if (!isCurrent()) return false
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
  store: ProposalSlice,
  proposalId: string,
): Promise<boolean> {
  if (!store.projectId) return false
  const projectId = store.projectId
  const session = store.projectSessionId
  const isCurrent = (): boolean =>
    store.projectSessionId === session && store.projectId === projectId
  store.error = null
  try {
    await acceptProposal(proposalId)
    await store.refreshWorkspace()
    // 接受会写入不可撤销 barrier（ACCEPT_AGENT_PROPOSAL），撤销可用性
    // 必须重新读取：refreshWorkspace 本身不刷新这组按钮。
    await store.refreshUndoRedoAvailability()
    if (!isCurrent()) return false
    store.pendingConfirmableProposals = store.pendingConfirmableProposals
      .filter((proposal) => proposal.proposalId !== proposalId)
    store.feedback = '已接受提案，Graph 已更新'
    return true
  } catch (err) {
    if (!isCurrent()) return false
    store.error = toDisplayError(err)
    return false
  }
}

/** 拒绝一个待确认提案：图保持不变，提案进入 REJECTED 终态。 */
export async function rejectConfirmableProposalAction(
  store: ProposalSlice,
  proposalId: string,
): Promise<boolean> {
  if (!store.projectId) return false
  const projectId = store.projectId
  const session = store.projectSessionId
  const isCurrent = (): boolean =>
    store.projectSessionId === session && store.projectId === projectId
  store.error = null
  try {
    await rejectProposal(proposalId)
    await store.refreshWorkspace()
    if (!isCurrent()) return false
    store.pendingConfirmableProposals = store.pendingConfirmableProposals
      .filter((proposal) => proposal.proposalId !== proposalId)
    store.feedback = '已拒绝提案，Graph 保持不变'
    return true
  } catch (err) {
    if (!isCurrent()) return false
    store.error = toDisplayError(err)
    return false
  }
}

/*
 * 拒绝一个待确认的 NodeQuery 提案。图保持不变;只有匹配的内存查询
 * (相同提案身份)被标记为 REJECTED——拒绝持久化提案 A 时,绝不触碰
 * 无关的当前查询 B。
 */
export async function rejectNodeQueryProposalAction(
  store: ProposalSlice,
  proposalId: string,
): Promise<boolean> {
  if (!store.projectId) return false
  const projectId = store.projectId
  const session = store.projectSessionId
  const isCurrent = (): boolean =>
    store.projectSessionId === session && store.projectId === projectId
  store.error = null
  try {
    await rejectProposal(proposalId)
    await store.loadNodeQueryProposals()
    if (!isCurrent()) return false
    if (store.nodeQuery && store.nodeQuery.proposalId === proposalId) {
      store.nodeQuery = { ...store.nodeQuery, status: 'REJECTED', proposalStatus: 'REJECTED' }
    }
    store.feedback = '已拒绝提案，Graph 保持不变'
    return true
  } catch (err) {
    if (!isCurrent()) return false
    store.error = toDisplayError(err)
    return false
  }
}
