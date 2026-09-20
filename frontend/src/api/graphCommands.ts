/**
 * Graph workspace mutation API: transactional commands, the typed operation
 * log behind Undo/Redo, semantic relations, and contextual node queries.
 *
 * All endpoints are Runtime commands — none of them call a model. Route
 * context is always explicit; a shared node never falls back to an
 * active/first/latest route to resolve its read context.
 */
import { apiClient } from './client'
import type { GraphWorkspaceRelationView } from './types'

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
  /** Display title of the compensated node when it is still resolvable. */
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
  /** Present when status === 'AWAITING_APPROVAL'. */
  proposalId?: string | null
  proposalStatus?: string | null
  actionFamily?: string | null
}

/** Result of accepting a pending NodeQuery proposal. */
export interface ProposalAcceptResult {
  proposalId: string
  status: string
  actionFamily: string | null
  producedNodeId: string | null
  relationId: string | null
  /** Originating run whose continuation chain may reopen; null for legacy flows. */
  originRunId?: string | null
}

/** Result of rejecting a pending NodeQuery proposal. */
export interface ProposalRejectResult {
  proposalId: string
  status: string
}

/**
 * Safe summary of one durable AgentProposal, with enough runtime identity to
 * reconnect a pending proposal to its NodeQuery anchor after a page reload.
 * triggerType is derived from the proposal's AgentRun and is the ONLY reliable
 * way to tell a NodeQuery proposal apart from an Answer/Decision one — every
 * run type carries an inputNodeId, so inputNodeId must never be used to infer
 * the query origin.
 */
export interface ProjectProposalSummary {
  proposalId: string
  runId: string | null
  /** AgentRunTriggerType code of the producing run (e.g. "node_query"). */
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
 * Optional server-side trigger-type narrowing of the shared proposal list.
 * Both fields are lists of AgentRunTriggerType codes (e.g. "node_query") and
 * are sent comma-separated; an absent/empty list means "no filter", so the
 * call stays backward compatible with the unfiltered list.
 */
export interface ProposalTriggerFilter {
  /** Keep only proposals whose producing run has one of these trigger types. */
  triggerTypes?: string[] | null
  /** Drop proposals whose producing run has one of these trigger types. */
  excludeTriggerTypes?: string[] | null
}

/** Lists durable proposals of a project, defaulting to the pending ones. */
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

/** Creates a standalone (floating) draft that starts disconnected from every
 * lineage. The creation-context route id is optional — a floating node may be
 * created with no Active route (routeId=null is legal).
 *
 * `nodeKind` selects the node's semantics: KNOWLEDGE (default, "+ 想法") or
 * RESOURCE (attached documents). A floating resource is authored first and
 * connected to a route later via {@link connectFloatingNode}. */
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
 * Connects a floating node into a route as its new tip. The backend only
 * accepts the route's current tip (never a historical insert) and rejects an
 * unanswered question as parent, so a hand-drawn connection cannot rewrite
 * lineage. The node keeps its id, kind and content.
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

/** Detaches the current tip from its route, restoring a floating node. */
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
 * Accepts a pending NodeQuery proposal. The backend currently takes only the
 * path-bound proposal id, so an empty body is sent. On success the caller is
 * expected to refresh the canonical graph because accepting a proposal may
 * produce new nodes/relations.
 */
export function acceptProposal(id: string): Promise<ProposalAcceptResult> {
  return apiClient.post<ProposalAcceptResult>(`/proposals/${id}/accept`, {})
}

/** Rejects a pending NodeQuery proposal; the graph is left unchanged. */
export function rejectProposal(id: string): Promise<ProposalRejectResult> {
  return apiClient.post<ProposalRejectResult>(`/proposals/${id}/reject`, {})
}
