import { API_BASE_URL, ApiError, GENERIC_ERROR_MESSAGE, apiClient } from '@/shared/http/client'
import type { SpecGenerationResponse, SpecSnapshotResponse } from '@/shared/contracts/types'

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

/**
 * Export variant of a spec snapshot's Markdown rendering. The backend renders
 * the stored snapshot deterministically — no model call, no second copy.
 *
 * - `snapshot`: faithful, provenance-complete export (audit view).
 * - `delivery`: development handoff document (PRD-style, provenance in
 *   appendix).
 */
export type SpecExportVariant = 'snapshot' | 'delivery'

/**
 * Downloads one snapshot's Markdown export and triggers a browser save.
 * Resolves after the download was handed to the browser; throws ApiError on
 * any failure. The backend is fetched directly (the typed client parses JSON
 * only), with the same error-contract handling.
 */
export async function downloadSpecMarkdown(
  snapshotId: string,
  variant: SpecExportVariant,
): Promise<void> {
  let response: Response
  try {
    response = await fetch(
      `${API_BASE_URL}/specs/${snapshotId}/export.md?variant=${variant}`,
    )
  } catch {
    throw new ApiError(GENERIC_ERROR_MESSAGE, 'NETWORK_ERROR', 0)
  }
  if (!response.ok) {
    throw await toApiError(response)
  }
  const blob = await response.blob()
  const shortId = snapshotId.slice(0, 8)
  const filename =
    variant === 'delivery'
      ? `开发需求文档-${shortId}.md`
      : `需求规格快照-${shortId}.md`
  const url = URL.createObjectURL(blob)
  try {
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = filename
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
  } finally {
    URL.revokeObjectURL(url)
  }
}

async function toApiError(response: Response): Promise<ApiError> {
  try {
    const parsed: unknown = await response.json()
    if (typeof parsed === 'object' && parsed !== null) {
      const candidate = parsed as { code?: unknown; message?: unknown }
      if (typeof candidate.code === 'string' && typeof candidate.message === 'string') {
        return new ApiError(candidate.message, candidate.code, response.status)
      }
    }
  } catch {
    // Fall through to the generic failure below.
  }
  return new ApiError(GENERIC_ERROR_MESSAGE, 'UNKNOWN_ERROR', response.status)
}
