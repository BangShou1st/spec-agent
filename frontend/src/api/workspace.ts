import { apiClient } from './client'
import type {
  ActiveProjectStateResponse,
  DraftQuestionResponse,
  RouteResponse,
} from './types'

/**
 * Workspace read API. Answer/repair mutations live in the async AgentRun
 * surface (api/agentRuns.ts); this module only exposes canonical reads.
 */
export function getActiveState(projectId: string): Promise<ActiveProjectStateResponse> {
  return apiClient.get<ActiveProjectStateResponse>(`/projects/${projectId}/active`)
}

export function listRoutes(projectId: string): Promise<RouteResponse[]> {
  return apiClient.get<RouteResponse[]>(`/projects/${projectId}/routes`)
}

/**
 * Draft the next question through the still-synchronous question endpoint.
 * Question reasoning moves to the decision runtime in a later cutover slice.
 */
export function draftNextQuestion(projectId: string): Promise<DraftQuestionResponse> {
  return apiClient.post<DraftQuestionResponse>(`/projects/${projectId}/questions/next`)
}
