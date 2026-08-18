import { apiClient } from './client'
import type { GraphWorkspaceView } from './types'

/**
 * Canonical project graph read API (Phase 7.3A).
 *
 * The graph is a read-only Runtime History Viewer projection: nodes are
 * deduplicated across routes, route membership is authoritative, and
 * route-specific answers stay separate. The frontend never reconstructs or
 * mutates this data.
 */
export function getProjectGraph(projectId: string): Promise<GraphWorkspaceView> {
  return apiClient.get<GraphWorkspaceView>(`/projects/${projectId}/graph`)
}
