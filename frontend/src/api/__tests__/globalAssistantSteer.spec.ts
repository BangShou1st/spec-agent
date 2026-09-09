import { describe, expect, it, vi } from 'vitest'
import { deleteGaThread, getGaThreadActivity, steerGaRun, stopGaThread } from '@/api/globalAssistant'

function jsonOk(body: unknown, status = 200): Response {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as unknown as Response
}

describe('global assistant steer/stop/delete api', () => {
  it('posts steer with message and uiContext', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonOk({ steerId: 's-1', status: 'QUEUED', interruptedRunId: 'r-1', successorRunId: null }))
    vi.stubGlobal('fetch', fetchMock)
    const result = await steerGaRun('r-1', '换方向', { currentPage: 'PROJECTS', selectedEntity: null })
    expect(result.steerId).toBe('s-1')
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain('/runs/r-1/steer')
    vi.unstubAllGlobals()
  })

  it('reads thread activity truth', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonOk({ activeRun: { runId: 'r-1', status: 'RUNNING' }, pendingSteer: null }))
    vi.stubGlobal('fetch', fetchMock)
    const act = await getGaThreadActivity('t-1')
    expect(act.activeRun?.runId).toBe('r-1')
    vi.unstubAllGlobals()
  })

  it('stops thread and deletes thread', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonOk({ activeRun: null, pendingSteer: null }))
      .mockResolvedValueOnce(jsonOk(undefined, 204))
    vi.stubGlobal('fetch', fetchMock)
    const stopped = await stopGaThread('t-1')
    expect(stopped.activeRun).toBeNull()
    await deleteGaThread('t-1')
    expect(fetchMock).toHaveBeenCalledTimes(2)
    vi.unstubAllGlobals()
  })
})
