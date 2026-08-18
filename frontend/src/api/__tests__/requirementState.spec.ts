import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '@/api/client'
import { getRequirementState, getRouteRequirementState } from '@/api/requirementState'
import type { RequirementStateView } from '@/api/types'

vi.mock('@/api/client', () => ({
  apiClient: { get: vi.fn() },
}))

const mockedGet = vi.mocked(apiClient.get)

const emptyState: RequirementStateView = {
  projectId: 'p1',
  routeId: null,
  confirmed: [],
  assumed: [],
  unresolved: [],
  rejected: [],
  builtAt: '2026-08-18T00:00:00Z',
}

describe('requirement state api', () => {
  beforeEach(() => {
    mockedGet.mockReset()
  })

  it('fetches the legacy active-route requirement state', async () => {
    mockedGet.mockResolvedValue(emptyState)

    const state = await getRequirementState('p1')

    expect(mockedGet).toHaveBeenCalledWith('/projects/p1/requirement-state')
    expect(state.projectId).toBe('p1')
  })

  it('fetches the route-scoped requirement state for an explicit route', async () => {
    mockedGet.mockResolvedValue({ ...emptyState, routeId: 'r2' })

    const state = await getRouteRequirementState('p1', 'r2')

    expect(mockedGet).toHaveBeenCalledWith('/projects/p1/routes/r2/requirement-state')
    expect(state.routeId).toBe('r2')
  })
})
