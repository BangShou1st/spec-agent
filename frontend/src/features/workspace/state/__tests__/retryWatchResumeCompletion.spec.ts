// 文件名:retryWatchResumeCompletion.spec.ts
// 用途:第三轮复核 R3-B 的闭合验证:硬刷新后由服务端清单续接的在途重试
// watcher(resumeInFlightRetryWatches)在 run 终态后必须走与主动重试
// 相同的共享收尾(finalizeRetryCompletionAction)——canonical 刷新
// (图/规格)+ 失败清单对账 + 反馈,而不是只清理失败清单。旧实现只调
// rebuildUnresolvedFailures,新产物永远不可见,用户必须再次手动刷新。
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const listUnresolvedRuns = vi.hoisted(() => vi.fn())
vi.mock('@/features/workspace/api/agentRuns', () => ({
  listUnresolvedRuns,
  listActiveRuns: vi.fn(async () => []),
  AGENT_RUN_MAX_POLLS: 3,
  AGENT_RUN_POLL_INTERVAL_MS: 0,
  isTerminalRunStatus: (status: string) => ['completed', 'failed'].includes(status),
}))
vi.mock('@/features/workspace/api/graph', () => ({ getProjectGraph: vi.fn() }))
vi.mock('@/features/projects/api/projects', () => ({ getProject: vi.fn() }))
vi.mock('@/features/workspace/api/requirementState', () => ({ getRequirementState: vi.fn() }))
vi.mock('@/features/workspace/api/workspace', () => ({
  getActiveState: vi.fn(),
  listRoutes: vi.fn(),
}))

import { rebuildUnresolvedFailuresAction } from '@/features/workspace/state/workspaceLoader'
import { useRunRegistryStore } from '@/features/workspace/state/runRegistryStore'
import type { UnresolvedFailure } from '@/features/workspace/api/agentRuns'

function inFlightRetryFailure(overrides: Partial<UnresolvedFailure> = {}): UnresolvedFailure {
  return {
    runId: 'f-1',
    projectId: 'p-1',
    operation: 'DRAFT_QUESTION',
    routeId: 'r-1',
    sourceNodeId: 'n-1',
    reasonCode: 'brain_timeout',
    reasonSummary: '模型服务响应超时',
    availableAction: 'RETRY_GENERATION',
    actionLabel: '重试生成',
    stale: false,
    retryRunId: 'r-1',
    retryStatus: 'running',
    createdAt: '2026-09-27T00:00:00Z',
    ...overrides,
  }
}

/** 最小 store 替身:只实现续接 watcher 与共享收尾触碰的成员。 */
function fakeStore(options: { pollOutcome?: 'completed' | 'failed' | 'unknown' } = {}) {
  return {
    projectId: 'p-1',
    projectSessionId: 1,
    feedback: null as string | null,
    error: null as unknown,
    nodeQuery: null,
    graphView: { nodes: ['old-node'] } as unknown,
    pollRunChainToTerminal: vi.fn(async () => options.pollOutcome ?? 'completed'),
    // canonical 刷新:模拟后端已产生新节点(重试成功后的真实产物)
    refreshWorkspace: vi.fn(async function (this: { graphView?: unknown }) {
      this.graphView = { nodes: ['old-node', 'new-node'] }
      return true
    }),
    rebuildUnresolvedFailures: vi.fn(async () => {}),
    refreshNodeQueryResult: vi.fn(async () => {}),
  }
}

async function settle(): Promise<void> {
  for (let i = 0; i < 10; i += 1) {
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
}

describe('resumed retry watcher shares the active-retry completion path (R3-B)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    listUnresolvedRuns.mockReset()
  })

  it('after hard refresh the in-flight retry completes with canonical refresh and failure reconciliation', async () => {
    listUnresolvedRuns.mockResolvedValue([inFlightRetryFailure()])
    const store = fakeStore()
    await rebuildUnresolvedFailuresAction(store as never)
    await settle()

    // 续接轮询在途重试 run
    expect(store.pollRunChainToTerminal).toHaveBeenCalledWith('r-1')
    // 修复前缺失的调用:成功后必须刷新 canonical 工作区(新产物自动可见)
    expect(store.refreshWorkspace).toHaveBeenCalled()
    expect(store.rebuildUnresolvedFailures).toHaveBeenCalled()
    expect((store.graphView as { nodes: string[] }).nodes).toContain('new-node')
    expect(store.feedback).toBe('已从上次失败处恢复')
  })

  it('a completed retry clears the failure entry from the server-side list', async () => {
    listUnresolvedRuns.mockResolvedValue([inFlightRetryFailure()])
    const store = fakeStore()
    await rebuildUnresolvedFailuresAction(store as never)
    await settle()
    const registry = useRunRegistryStore()
    // 终态成功后服务端清单对账:失败条目消失(不再有重试中的禁用入口)
    expect(registry.failureList).toHaveLength(0)
  })

  it('unknown outcome is not disguised as success', async () => {
    listUnresolvedRuns.mockResolvedValue([inFlightRetryFailure()])
    const store = fakeStore({ pollOutcome: 'unknown' })
    await rebuildUnresolvedFailuresAction(store as never)
    await settle()

    expect(store.pollRunChainToTerminal).toHaveBeenCalledWith('r-1')
    // canonical 刷新与清单对账仍然发生(对账是"检查状态",不是伪装成功)
    expect(store.refreshWorkspace).toHaveBeenCalled()
    // 但绝不显示"已恢复"
    expect(store.feedback).toBeNull()
  })

  it('a failed retry hands over to the latest failure without a success toast', async () => {
    listUnresolvedRuns.mockResolvedValue([inFlightRetryFailure()])
    const store = fakeStore({ pollOutcome: 'failed' })
    await rebuildUnresolvedFailuresAction(store as never)
    await settle()

    expect(store.refreshWorkspace).toHaveBeenCalled()
    expect(store.feedback).toBeNull()
  })

  it('a session switch during polling never writes stale results into the new session', async () => {
    listUnresolvedRuns.mockResolvedValue([inFlightRetryFailure()])
    const store = fakeStore()
    // 轮询期间项目切换:watcher 的会话身份检查让一切收尾落空
    store.pollRunChainToTerminal.mockImplementation(async () => {
      store.projectSessionId = 2
      store.projectId = 'p-2'
      return 'completed'
    })
    await rebuildUnresolvedFailuresAction(store as never)
    await settle()

    expect(store.pollRunChainToTerminal).toHaveBeenCalledWith('r-1')
    expect(store.refreshWorkspace).not.toHaveBeenCalled()
    expect(store.feedback).toBeNull()
  })

  it('duplicate watchers are not attached for the same retry run', async () => {
    // 第一次对账:重试在途;第二次对账(共享收尾触发):run 已终态
    listUnresolvedRuns
      .mockResolvedValueOnce([inFlightRetryFailure()])
      .mockResolvedValue([inFlightRetryFailure({ retryStatus: 'completed' })])
    const store = fakeStore()
    await rebuildUnresolvedFailuresAction(store as never)
    await settle()
    await rebuildUnresolvedFailuresAction(store as never)
    await settle()
    // 同一 retryRunId 只挂一个观察者:对账时已终态的 run 不再续接
    expect(store.pollRunChainToTerminal).toHaveBeenCalledTimes(1)
  })
})
