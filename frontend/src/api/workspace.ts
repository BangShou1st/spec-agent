import { apiClient } from './client'
import type {
  ActiveProjectStateResponse,
  RouteResponse,
} from './types'

/**
 * Workspace read API. Answer/repair/question-draft mutations live in the
 * async AgentRun surface (api/agentRuns.ts); this module only exposes
 * canonical reads.
 */
export function getActiveState(projectId: string): Promise<ActiveProjectStateResponse> {
  return apiClient.get<ActiveProjectStateResponse>(`/projects/${projectId}/active`)
}

export function listRoutes(projectId: string): Promise<RouteResponse[]> {
  return apiClient.get<RouteResponse[]>(`/projects/${projectId}/routes`)
}
