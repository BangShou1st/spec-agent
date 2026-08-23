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

/** Attaches a user-authored resource node (root of empty route or tip append). */
export function attachResource(
  projectId: string,
  routeId: string,
  parentNodeId: string | null,
  subtype: ResourceSubtype,
  content: Record<string, unknown>,
): Promise<CreatedNodeResponse> {
  return apiClient.post<CreatedNodeResponse>(`/projects/${projectId}/resources`, {
    routeId,
    parentNodeId,
    subtype,
    content,
  })
}

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
  routeId: string,
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
