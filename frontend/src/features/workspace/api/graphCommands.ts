// 文件名:graphCommands.ts
// 用途:图工作台的变更 API:事务性命令、支撑撤销/重做的类型化操作日志、
//       语义关系与上下文节点查询。
// 所有端点都是运行时命令——没有任何一个会调用模型。路线上下文始终显式;
// 共享节点绝不回退到 active/first/latest 路线来解析阅读上下文。

import { apiClient } from '@/shared/http/client'
import type { GraphWorkspaceRelationView } from '@/shared/contracts/types'

export interface DraftNodePayload {
  subtype: string
  content: Record<string, unknown>
}

export interface CreatedNodeResponse {
  id: string
  routeId: string | null
  branched: boolean
  kind: string
  subtype: string
  content: Record<string, unknown>
  authorKind: string
  knowledgeStatus: string | null
}

export interface UndoRedoAvailability {
  canUndo: boolean
  canRedo: boolean
}

export interface UndoRedoResult {
  operation: {
    id: string
    type: string
    status: string
  }
  description: string
  /** 被补偿节点仍可解析时的展示标题。 */
  targetTitle: string | null
}

export interface NodeQueryRunCreated {
  runId: string
  phase: string
}

export interface NodeQueryRunResult {
  runId: string
  status: string
  producedNodeId: string | null
  message: string | null
  /** status === 'AWAITING_APPROVAL' 时存在。 */
  proposalId?: string | null
  proposalStatus?: string | null
  actionFamily?: string | null
}

/** 接受一个待定的 NodeQuery 提案的结果。 */
export interface ProposalAcceptResult {
  proposalId: string
  status: string
  actionFamily: string | null
  producedNodeId: string | null
  relationId: string | null
  /** 来源运行的续跑链可能被重新打开;旧流程为 null。 */
  originRunId?: string | null
}

/** 拒绝一个待定 NodeQuery 提案的结果。 */
export interface ProposalRejectResult {
  proposalId: string
  status: string
}

/**
 * 单个持久化 AgentProposal 的安全摘要,带有足够的运行时身份,
 * 使页面刷新后仍能把待定提案重新挂回它的 NodeQuery 锚点。
 * triggerType 由提案的 AgentRun 派生,是区分 NodeQuery 提案与
 * Answer/Decision 提案的唯一可靠依据——每种运行类型都带 inputNodeId,
 * 因此绝不能用 inputNodeId 推断查询来源。
 */
export interface ProjectProposalSummary {
  proposalId: string
  runId: string | null
  /** 产生该提案的运行的 AgentRunTriggerType 代码(如 "node_query")。 */
  triggerType: string | null
  inputNodeId: string | null
  routeId: string | null
  actionFamily: string
  status: string
  createdAt: string
  decidedAt: string | null
  decidedBy: string | null
}

/**
 * 对共享提案列表做可选的服务端 trigger-type 收窄。
 * 两个字段都是 AgentRunTriggerType 代码列表(如 "node_query"),以逗号分隔发送;
 * 缺省/空列表表示"不过滤",调用因此对未过滤列表保持向后兼容。
 */
export interface ProposalTriggerFilter {
  /** 只保留产生运行的 trigger type 属于这些的提案。 */
  triggerTypes?: string[] | null
  /** 丢弃产生运行的 trigger type 属于这些的提案。 */
  excludeTriggerTypes?: string[] | null
}

/** 列出项目的持久化提案,默认只看待定的。 */
export function listProposals(
  projectId: string,
  status = 'PROPOSED',
  filter: ProposalTriggerFilter = {},
): Promise<ProjectProposalSummary[]> {
  const params = new URLSearchParams({ status })
  if (filter.triggerTypes?.length) {
    params.set('triggerType', filter.triggerTypes.join(','))
  }
  if (filter.excludeTriggerTypes?.length) {
    params.set('excludeTriggerType', filter.excludeTriggerTypes.join(','))
  }
  return apiClient.get<ProjectProposalSummary[]>(
    `/projects/${projectId}/proposals?${params.toString()}`,
  )
}

export function createRootDraftNode(
  projectId: string,
  routeId: string,
  payload: DraftNodePayload,
): Promise<CreatedNodeResponse> {
  return apiClient.post<CreatedNodeResponse>(
    `/projects/${projectId}/nodes`,
    { routeId, ...payload },
  )
}

/** 创建一个独立(浮动)草稿节点,初始与所有谱系断开。
 * 创建上下文的路线 id 可选——浮动节点可以在没有 Active 路线时创建(routeId=null 合法)。
 *
 * `nodeKind` 决定节点语义:KNOWLEDGE(默认,"+ 想法")或
 * RESOURCE(附件文档)。浮动资源先编写内容,之后通过 {@link connectFloatingNode}
 * 接入某条路线。 */
export function createFloatingDraftNode(
  projectId: string,
  routeId: string | null,
  payload: DraftNodePayload & { nodeKind?: 'KNOWLEDGE' | 'RESOURCE' },
): Promise<CreatedNodeResponse> {
  return apiClient.post<CreatedNodeResponse>(
    `/projects/${projectId}/floating-nodes`,
    { routeId, ...payload },
  )
}

/**
 * 把浮动节点接入某条路线,成为其新的末端。后端只接受路线当前末端
 * (绝不做历史插入),并且拒绝把未回答的问题当作父节点,因此手工连线
 * 不可能改写谱系。节点保留自己的 id、kind 和内容。
 */
export function connectFloatingNode(
  projectId: string,
  nodeId: string,
  routeId: string,
  parentNodeId: string | null,
): Promise<CreatedNodeResponse> {
  return apiClient.post<CreatedNodeResponse>(
    `/projects/${projectId}/nodes/${nodeId}/connect`,
    { routeId, parentNodeId },
  )
}

/** 把当前末端从其路线分离,恢复为浮动节点。 */
export function disconnectNode(
  projectId: string,
  nodeId: string,
  routeId: string,
): Promise<CreatedNodeResponse> {
  return apiClient.post<CreatedNodeResponse>(
    `/projects/${projectId}/nodes/${nodeId}/disconnect`,
    { routeId },
  )
}

export function appendContinuation(
  projectId: string,
  nodeId: string,
  routeId: string,
  payload: DraftNodePayload,
): Promise<CreatedNodeResponse> {
  return apiClient.post<CreatedNodeResponse>(
    `/projects/${projectId}/nodes/${nodeId}/continuation`,
    { routeId, ...payload },
  )
}

export function reviseDraftNode(
  projectId: string,
  nodeId: string,
  payload: DraftNodePayload,
): Promise<CreatedNodeResponse> {
  return apiClient.patch<CreatedNodeResponse>(
    `/projects/${projectId}/nodes/${nodeId}/draft`,
    payload,
  )
}

export function setKnowledgeStatus(
  projectId: string,
  nodeId: string,
  status: 'PROPOSED' | 'CONFIRMED' | 'CHALLENGED' | 'SUPERSEDED',
): Promise<CreatedNodeResponse> {
  return apiClient.post<CreatedNodeResponse>(
    `/projects/${projectId}/nodes/${nodeId}/knowledge-status`,
    { status },
  )
}

export type ResourceSubtype = 'TEXT' | 'URL' | 'FILE' | 'IMAGE' | 'REPOSITORY' | 'API_DOCUMENTATION'

export function listRelations(projectId: string): Promise<GraphWorkspaceRelationView[]> {
  return apiClient.get<GraphWorkspaceRelationView[]>(`/projects/${projectId}/relations`)
}

export function createRelation(
  projectId: string,
  sourceNodeId: string,
  targetNodeId: string,
  relationType: GraphWorkspaceRelationView['relationType'],
): Promise<GraphWorkspaceRelationView> {
  return apiClient.post<GraphWorkspaceRelationView>(`/projects/${projectId}/relations`, {
    sourceNodeId,
    targetNodeId,
    relationType,
  })
}

export function getUndoRedoAvailability(projectId: string): Promise<UndoRedoAvailability> {
  return apiClient.get<UndoRedoAvailability>(
    `/projects/${projectId}/graph-operations/availability`,
  )
}

export function undoGraphOperation(projectId: string): Promise<UndoRedoResult> {
  return apiClient.post<UndoRedoResult>(`/projects/${projectId}/graph-operations/undo`)
}

export function redoGraphOperation(projectId: string): Promise<UndoRedoResult> {
  return apiClient.post<UndoRedoResult>(`/projects/${projectId}/graph-operations/redo`)
}

export function createNodeQuery(
  projectId: string,
  nodeId: string,
  routeId: string | null,
  question: string,
): Promise<NodeQueryRunCreated> {
  return apiClient.post<NodeQueryRunCreated>(
    `/projects/${projectId}/nodes/${nodeId}/query`,
    { routeId, question },
  )
}

export function getNodeQueryResult(
  projectId: string,
  nodeId: string,
  runId: string,
): Promise<NodeQueryRunResult> {
  return apiClient.get<NodeQueryRunResult>(
    `/projects/${projectId}/nodes/${nodeId}/query/${runId}`,
  )
}

/**
 * 接受一个待定的 NodeQuery 提案。后端目前只取路径中的提案 id,因此发送空请求体。
 * 成功后调用方应刷新规范图,因为接受提案可能产生新的节点/关系。
 */
export function acceptProposal(id: string): Promise<ProposalAcceptResult> {
  return apiClient.post<ProposalAcceptResult>(`/proposals/${id}/accept`, {})
}

/** 拒绝一个待定的 NodeQuery 提案;图保持不变。 */
export function rejectProposal(id: string): Promise<ProposalRejectResult> {
  return apiClient.post<ProposalRejectResult>(`/proposals/${id}/reject`, {})
}
