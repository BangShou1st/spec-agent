import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useRunRegistryStore } from '@/features/workspace/state/runRegistryStore'
import type { AgentRunView } from '@/features/workspace/api/agentRuns'

function viewOf(overrides: Partial<AgentRunView> & { runId: string }): AgentRunView {
  return {
    projectId: 'p1',
    routeId: 'r1',
    operation: 'ANSWER_TIP',
    status: 'running',
    phase: 'STATE_UPDATING',
    producedNodeId: null,
    producedAnswerId: null,
    producedPatchId: null,
    producedSpecSnapshotId: null,
    ...overrides,
  }
}

describe('runRegistryStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('feeds run views into independent per-run entries', () => {
    const registry = useRunRegistryStore()
    registry.register({ runId: 'run-a', operation: 'ANSWER_TIP', routeId: 'r1', sourceNodeId: 'n1' })
    registry.register({ runId: 'run-b', operation: 'DRAFT_QUESTION', routeId: 'r2', sourceNodeId: null })

    registry.feed(viewOf({ runId: 'run-a', routeId: 'r1', phase: 'DECIDING' }))
    registry.feed(viewOf({ runId: 'run-b', routeId: 'r2', operation: 'DRAFT_QUESTION' }))

    const [a, b] = registry.list
    expect(a.sourceNodeId).toBe('n1')
    expect(a.phase).toBe('DECIDING')
    expect(a.status).toBe('RUNNING')
    expect(b.sourceNodeId).toBeNull()
    expect(b.status).toBe('RUNNING')
    expect(registry.inFlight).toHaveLength(2)
  })

  it('keeps composed progress from the whitelisted read model', () => {
    const registry = useRunRegistryStore()
    registry.feed(viewOf({
      runId: 'run-a',
      status: 'running',
      progress: {
        phase: 'STATE_UPDATED',
        summary: '需求要点整理完成，共 2 条',
        steps: [{
          sequence: 1,
          phase: 'STATE_UPDATED',
          event: 'PROCESS_NOTE',
          summary: '需求要点整理完成，共 2 条',
          items: ['要点一'],
          at: '2026-01-01T00:00:00.000Z',
        }],
      },
    }))
    const entry = registry.runs['run-a']
    expect(entry.summary).toBe('需求要点整理完成，共 2 条')
    expect(entry.steps).toHaveLength(1)
  })

  it('rebuild preserves submit-time sourceNodeId and keeps in-flight entries missing from the listing', () => {
    const registry = useRunRegistryStore()
    registry.register({ runId: 'run-a', operation: 'ANSWER_TIP', routeId: 'r1', sourceNodeId: 'n1' })
    registry.register({ runId: 'run-gone', operation: 'ANSWER_TIP', routeId: 'r1', sourceNodeId: 'n2' })
    registry.register({ runId: 'run-done', operation: 'ANSWER_TIP', routeId: 'r1', sourceNodeId: 'n3' })
    registry.feed(viewOf({ runId: 'run-done', status: 'completed' }))

    // run-gone is RUNNING but absent from the backend listing (still polled);
    // run-done is terminal and absent — success is canonical, so it is dropped.
    registry.rebuild([viewOf({ runId: 'run-a', routeId: 'r1' })])

    expect(Object.keys(registry.runs).sort()).toEqual(['run-a', 'run-gone'])
    expect(registry.runs['run-a'].sourceNodeId).toBe('n1')
    expect(registry.runs['run-gone'].sourceNodeId).toBe('n2')
  })

  it('rebuild drops terminal succeeded entries and keeps failures visible', () => {
    const registry = useRunRegistryStore()
    registry.feed(viewOf({ runId: 'run-failed', status: 'failed' }))
    registry.feed(viewOf({ runId: 'run-ok', status: 'completed' }))

    registry.rebuild([])

    expect(registry.runs['run-ok']).toBeUndefined()
    expect(registry.runs['run-failed']?.status).toBe('FAILED')
  })

  it('clear removes everything on project switch', () => {
    const registry = useRunRegistryStore()
    registry.feed(viewOf({ runId: 'run-a' }))
    registry.clear()
    expect(registry.list).toHaveLength(0)
  })
})
