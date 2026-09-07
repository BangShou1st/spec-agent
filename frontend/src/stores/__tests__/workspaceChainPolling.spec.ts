import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { getAgentRun } from '@/api/agentRuns'
import type { AgentRunView } from '@/api/agentRuns'
import { acceptProposal as apiAcceptProposal } from '@/api/graphCommands'

vi.mock('@/api/agentRuns', async () => ({
  ...(await vi.importActual<typeof import('@/api/agentRuns')>('@/api/agentRuns')),
  createAgentRun: vi.fn(),
  getAgentRun: vi.fn(),
}))

vi.mock('@/api/graphCommands', () => ({
  acceptProposal: vi.fn(),
  appendContinuation: vi.fn(),
  attachResource: vi.fn(),
  createFloatingDraftNode: vi.fn(),
  createRelation: vi.fn(),
  createNodeQuery: vi.fn(),
  getNodeQueryResult: vi.fn(),
  getUndoRedoAvailability: vi.fn(),
  listProposals: vi.fn(),
  redoGraphOperation: vi.fn(),
  rejectProposal: vi.fn(),
  reviseDraftNode: vi.fn(),
  setKnowledgeStatus: vi.fn(),
  undoGraphOperation: vi.fn(),
}))

const mockedGetAgentRun = vi.mocked(getAgentRun)
const mockedAcceptProposal = vi.mocked(apiAcceptProposal)

function chainRunView(overrides: Partial<AgentRunView> = {}): AgentRunView {
  return {
    runId: 'run-1',
    projectId: 'p1',
    routeId: 'r1',
    operation: 'ANSWER_TIP',
    status: 'completed',
    phase: 'COMPLETED',
    producedNodeId: null,
    producedAnswerId: null,
    producedPatchId: null,
    producedSpecSnapshotId: null,
    childRunId: null,
    continuationPending: false,
    respondMessage: null,
    ...overrides,
  }
}

describe('autonomous run chain polling (Closure B)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('F1: follows the child instead of settling on a COMPLETED parent', async () => {
    const store = useWorkspaceStore()
    store.projectId = 'p1'
    store.refreshWorkspace = vi.fn().mockResolvedValue(true) as never
    mockedGetAgentRun.mockImplementation(async (_projectId: string, runId: string) => {
      if (runId === 'run-1') return chainRunView({ runId: 'run-1', childRunId: 'run-2' })
      return chainRunView({ runId: 'run-2', respondMessage: 'leaf done' })
    })
    const leaf = await store.pollRunChainToTerminal('run-1')
    expect(leaf).not.toBe('failed')
    expect(leaf).not.toBe('unknown')
    if (leaf === 'failed' || leaf === 'unknown') throw new Error('expected a leaf view')
    expect(leaf.runId).toBe('run-2')
    expect(mockedGetAgentRun).toHaveBeenCalledWith('p1', 'run-2')
  })

  it('F2: waits through continuationPending then follows the late child', async () => {
    const store = useWorkspaceStore()
    store.projectId = 'p1'
    store.refreshWorkspace = vi.fn().mockResolvedValue(true) as never
    let calls = 0
    mockedGetAgentRun.mockImplementation(async (_projectId: string, runId: string) => {
      calls += 1
      if (runId === 'run-1' && calls === 1) {
        return chainRunView({ runId: 'run-1', continuationPending: true })
      }
      if (runId === 'run-1') {
        return chainRunView({ runId: 'run-1', childRunId: 'run-2' })
      }
      return chainRunView({ runId: 'run-2' })
    })
    const leaf = await store.pollRunChainToTerminal('run-1')
    if (leaf === 'failed' || leaf === 'unknown') throw new Error('expected a leaf view')
    expect(leaf.runId).toBe('run-2')
    expect(mockedGetAgentRun).toHaveBeenCalledWith('p1', 'run-2')
  })

  it('F3: surfaces the terminal leaf respondMessage in feedback', async () => {
    const store = useWorkspaceStore()
    store.projectId = 'p1'
    store.refreshWorkspace = vi.fn().mockResolvedValue(true) as never
    mockedGetAgentRun.mockResolvedValue(chainRunView({ respondMessage: '最终回答' }))
    const leaf = await store.pollRunChainToTerminal('run-1')
    if (leaf === 'failed' || leaf === 'unknown') throw new Error('expected a leaf view')
    store.feedback = leaf.respondMessage ?? 'fallback'
    expect(store.feedback).toBe('最终回答')
  })

  it('F4: submitAnswer only cleans up after the child chain is terminal', async () => {
    const store = useWorkspaceStore()
    store.projectId = 'p1'
    store.refreshWorkspace = vi.fn().mockResolvedValue(true) as never
    store.pendingAnswerNodeId = 'q3'
    const finishSpy = vi.spyOn(store, 'finishSuccessfulAnswerRun')
    mockedGetAgentRun.mockImplementation(async (_projectId: string, runId: string) => {
      if (runId === 'run-1') return chainRunView({ runId: 'run-1', childRunId: 'run-2' })
      return chainRunView({ runId: 'run-2', respondMessage: 'chain answer' })
    })
    await store.pollAnswerRun('run-1')
    expect(finishSpy).toHaveBeenCalledTimes(1)
    expect(store.feedback).toBe('chain answer')
    expect(store.pendingAnswerNodeId).toBeNull()
  })

  it('F5: acceptProposal with originRunId follows the origin chain before refresh', async () => {
    const store = useWorkspaceStore()
    store.projectId = 'p1'
    const refreshSpy = vi.fn().mockResolvedValue(true)
    store.refreshWorkspace = refreshSpy as never
    store.loadNodeQueryProposals = vi.fn().mockResolvedValue(undefined) as never
    mockedAcceptProposal.mockResolvedValue({
      proposalId: 'prop-1',
      status: 'ACCEPTED',
      actionFamily: 'CREATE_NODE',
      producedNodeId: 'n9',
      relationId: null,
      originRunId: 'run-origin',
    })
    const seen: string[] = []
    mockedGetAgentRun.mockImplementation(async (_projectId: string, runId: string) => {
      seen.push(runId)
      if (runId === 'run-origin') return chainRunView({ runId: 'run-origin', childRunId: 'run-leaf' })
      return chainRunView({ runId: 'run-leaf' })
    })
    const ok = await store.acceptNodeQueryProposal('prop-1')
    expect(ok).toBe(true)
    expect(seen).toContain('run-origin')
    expect(seen).toContain('run-leaf')
    expect(refreshSpy).toHaveBeenCalled()
  })
})
