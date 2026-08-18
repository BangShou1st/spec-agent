import { apiClient } from './client'
import type { RequirementStateView } from './types'

/** Legacy active-route requirement-state read (unchanged). */
export function getRequirementState(projectId: string): Promise<RequirementStateView> {
  return apiClient.get<RequirementStateView>(`/projects/${projectId}/requirement-state`)
}

/** Route-scoped requirement-state read for an explicit route (Phase 7.3A). */
export function getRouteRequirementState(
  projectId: string,
  routeId: string,
): Promise<RequirementStateView> {
  return apiClient.get<RequirementStateView>(
    `/projects/${projectId}/routes/${routeId}/requirement-state`,
  )
}
