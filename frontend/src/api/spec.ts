import { apiClient } from './client'
import type { SpecGenerationResponse, SpecSnapshotResponse } from './types'

/**
 * Spec read + generation API.
 *
 * The frontend never authors a SpecSnapshot: generation goes through the
 * existing backend command and the resulting derived artifact is re-read from
 * the backend. Snapshots are derived, never source of truth.
 */

export function generateSpec(projectId: string): Promise<SpecGenerationResponse> {
  return apiClient.post<SpecGenerationResponse>(`/projects/${projectId}/specs/generate`)
}

export function listRouteSpecs(
  projectId: string,
  routeId: string,
): Promise<SpecSnapshotResponse[]> {
  return apiClient.get<SpecSnapshotResponse[]>(
    `/projects/${projectId}/routes/${routeId}/specs`,
  )
}

export function getSpecSnapshot(snapshotId: string): Promise<SpecSnapshotResponse> {
  return apiClient.get<SpecSnapshotResponse>(`/specs/${snapshotId}`)
}
