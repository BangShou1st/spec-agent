import { apiClient } from './client'
import type { RequirementStateView } from './types'

export function getRequirementState(projectId: string): Promise<RequirementStateView> {
  return apiClient.get<RequirementStateView>(`/projects/${projectId}/requirement-state`)
}