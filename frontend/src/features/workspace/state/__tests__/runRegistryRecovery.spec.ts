// 文件名:runRegistryRecovery.spec.ts
// 用途:任务级失败恢复的注册表单测(R6 前端部分):
//   - 失败清单以 failedRunId 为键,服务端事实整体对账;
//   - failuresForNode 按 (sourceNodeId, routeId) 精确绑定——共享节点上
//     不同路线的失败互不覆盖;
//   - 乐观 retrying 标记与服务端 retryRunId 的衔接;
//   - 已被取代的 FAILED run 从注册表移除,不渲染僵尸占位卡。
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useRunRegistryStore } from '@/features/workspace/state/runRegistryStore'
import type { UnresolvedFailure } from '@/features/workspace/api/agentRuns'

function failure(overrides: Partial<UnresolvedFailure> & { runId: string }): UnresolvedFailure {
  return {
    projectId: 'p-1',
    operation: 'DRAFT_QUESTION',
    routeId: 'r-1',
    sourceNodeId: 'n-1',
    reasonCode: 'brain_timeout',
    reasonSummary: '模型服务响应超时',
    availableAction: 'RETRY_GENERATION',
    actionLabel: '重试生成',
    stale: false,
    retryRunId: null,
    retryStatus: null,
    createdAt: '2026-09-27T00:00:00Z',
    ...overrides,
  }
}

describe('runRegistryStore failure recovery registry', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('rebuilds failures from server truth and reports failures per node+route', () => {
    const store = useRunRegistryStore()
    store.rebuildFailures([
      failure({ runId: 'f-1', sourceNodeId: 'n-1', routeId: 'r-1' }),
      failure({ runId: 'f-2', sourceNodeId: 'n-1', routeId: 'r-2' }),
    ])
    expect(store.failureList).toHaveLength(2)
    // 共享节点 n-1:两条路线的失败按 routeId 精确隔离
    expect(store.failuresForNode('n-1', 'r-1').map((f) => f.runId)).toEqual(['f-1'])
    expect(store.failuresForNode('n-1', 'r-2').map((f) => f.runId)).toEqual(['f-2'])
    // 无路线失败只匹配无路线阅读态
    store.rebuildFailures([
      failure({ runId: 'f-3', sourceNodeId: 'n-2', routeId: null }),
    ])
    expect(store.failuresForNode('n-2', null).map((f) => f.runId)).toEqual(['f-3'])
    expect(store.failuresForNode('n-2', 'r-1')).toEqual([])
  })

  it('tracks optimistic retry state and reconciles with server retryRunId', () => {
    const store = useRunRegistryStore()
    store.rebuildFailures([failure({ runId: 'f-1' })])
    expect(store.isRetrying('f-1')).toBe(false)
    store.markRetryStarted('f-1', 'r-9')
    expect(store.isRetrying('f-1')).toBe(true)
    // 服务端确认同一重试 run:乐观标记清除,以服务端为准
    store.rebuildFailures([failure({ runId: 'f-1', retryRunId: 'r-9', retryStatus: 'running' })])
    expect(store.isRetrying('f-1')).toBe(true)
    // 失败已被解决:清单移除,重试标记随之消失
    store.rebuildFailures([])
    expect(store.isRetrying('f-1')).toBe(false)
    expect(store.failureList).toHaveLength(0)
  })

  it('clears superseded FAILED runs from the registry so failed cards do not stack', () => {
    const store = useRunRegistryStore()
    // 失败占位卡条目在 registry.runs 中
    store.register({ runId: 'f-1', operation: 'DRAFT_QUESTION', routeId: 'r-1', sourceNodeId: null })
    store.feed({
      runId: 'f-1', projectId: 'p-1', routeId: 'r-1', operation: 'DRAFT_QUESTION',
      status: 'failed', phase: 'FAILED', producedNodeId: null, producedAnswerId: null,
      producedPatchId: null, producedSpecSnapshotId: null,
    } as never)
    // 服务端清单已不包含 f-1(被后续成功/新失败取代)
    store.rebuildFailures([])
    expect(store.runs['f-1']).toBeUndefined()
  })

  it('clear() also resets failures and retrying state on project switch', () => {
    const store = useRunRegistryStore()
    store.rebuildFailures([failure({ runId: 'f-1' })])
    store.markRetryStarted('f-1', 'r-9')
    store.clear()
    expect(store.failureList).toHaveLength(0)
    expect(store.isRetrying('f-1')).toBe(false)
  })
})
