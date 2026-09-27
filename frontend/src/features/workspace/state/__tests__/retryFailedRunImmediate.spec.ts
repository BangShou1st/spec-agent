// 文件名:retryFailedRunImmediate.spec.ts
// 用途:6-2 的前端即时状态验证:乐观 retrying 标记必须在请求发出之前
// 写入(按钮立即禁用/进度立即显示),不等网络往返;失败、结果未知、
// 会话切换都有对应的清理路径。后端幂等仍保留(同一恢复键的服务端
// 幂等语义由后端集成测试覆盖)。
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const retryAgentRun = vi.hoisted(() => vi.fn())
vi.mock('@/features/workspace/api/agentRuns', () => ({
  AGENT_RUN_MAX_POLLS: 3,
  AGENT_RUN_POLL_INTERVAL_MS: 0,
  createAgentRun: vi.fn(),
  getAgentRun: vi.fn(),
  isTerminalRunStatus: (status: string) =>
    ['completed', 'failed'].includes(status),
  retryAgentRun,
}))

import { useRunRegistryStore } from '@/features/workspace/state/runRegistryStore'
import { retryFailedRunAction } from '@/features/workspace/state/workspaceRuns'
import type { UnresolvedFailure } from '@/features/workspace/api/agentRuns'

function failure(overrides: Partial<UnresolvedFailure> = {}): UnresolvedFailure {
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
    retryRunId: null,
    retryStatus: null,
    createdAt: '2026-09-27T00:00:00Z',
    ...overrides,
  }
}

/** 最小 AnswerRunSlice 测试替身:只实现 retryFailedRunAction 触碰的成员。 */
function fakeStore(overrides: {
  pollOutcome?: 'completed' | 'failed' | 'unknown'
  switchSessionAfterAwait?: boolean
  apiDelayMs?: number
} = {}) {
  let calls = 0
  return {
    projectId: 'p-1',
    projectSessionId: 's-1',
    feedback: null as string | null,
    error: null as unknown,
    nodeQuery: null,
    pollRunChainToTerminal: vi.fn(async () => {
      calls += 1
      if (overrides.switchSessionAfterAwait && calls === 1) {
        // 模拟轮询期间会话切换:isCurrent() 变 false
        ;(storeHolder.store as { projectSessionId?: string }).projectSessionId = 's-2'
      }
      return overrides.pollOutcome ?? 'completed'
    }),
    refreshWorkspace: vi.fn(async () => true),
    rebuildUnresolvedFailures: vi.fn(async () => {}),
  }
}

// retryFailedRunAction 在同一个 store 引用上读取身份;用 holder 让
// pollRunChainToTerminal 能改变会话身份。
const storeHolder: { store: Record<string, unknown> } = { store: {} }

function buildStore(overrides: Parameters<typeof fakeStore>[0] = {}) {
  const store = fakeStore(overrides)
  storeHolder.store = store as unknown as Record<string, unknown>
  return store
}

describe('retryFailedRunAction immediate optimistic state (6-2)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    retryAgentRun.mockReset()
  })

  it('marks retrying BEFORE the request resolves (deferred response)', async () => {
    const store = buildStore()
    const registry = useRunRegistryStore()
    let resolveApi: (value: { runId: string }) => void = () => {}
    retryAgentRun.mockReturnValue(new Promise<{ runId: string }>((resolve) => {
      resolveApi = resolve
    }))

    const action = retryFailedRunAction(store as never, failure())
    // 请求尚未返回:乐观标记必须已经生效(按钮禁用/进度显示的依据)
    await Promise.resolve()
    expect(retryAgentRun).toHaveBeenCalledTimes(1)
    expect(registry.isRetrying('f-1')).toBe(true)

    resolveApi({ runId: 'r-new' })
    await action
    expect(store.pollRunChainToTerminal).toHaveBeenCalledWith('r-new')
    // 成功后乐观标记被真实 runId 取代并最终清除
    expect(registry.isRetrying('f-1')).toBe(false)
  })

  it('double click while request is in flight does not submit twice', async () => {
    const store = buildStore()
    retryAgentRun.mockReturnValue(new Promise(() => {})) // 永不返回的慢响应

    void retryFailedRunAction(store as never, failure())
    await Promise.resolve()
    const second = await retryFailedRunAction(store as never, failure())
    expect(second).toBe(false)
    expect(retryAgentRun).toHaveBeenCalledTimes(1)
  })

  it('rolls back the optimistic mark when the outcome is failed or unknown', async () => {
    const registry = useRunRegistryStore()
    for (const outcome of ['failed', 'unknown'] as const) {
      registry.retrying = {}
      const store = buildStore({ pollOutcome: outcome })
      const result = await retryFailedRunAction(store as never, failure())
      expect(result).toBe(false)
      expect(registry.isRetrying('f-1')).toBe(false)
    }
  })

  it('rolls back the optimistic mark when the request errors', async () => {
    const store = buildStore()
    const registry = useRunRegistryStore()
    retryAgentRun.mockRejectedValue(new Error('network down'))
    const result = await retryFailedRunAction(store as never, failure())
    expect(result).toBe(false)
    expect(registry.isRetrying('f-1')).toBe(false)
    // 非 ApiError 的异常展示通用文案,不泄漏内部细节
    expect(store.error).toEqual({
      code: 'UNKNOWN_ERROR',
      message: '操作失败，请稍后重试',
    })
  })

  it('does not write stale feedback after a project/session switch', async () => {
    const registry = useRunRegistryStore()
    registry.retrying = {}
    const store = buildStore({ switchSessionAfterAwait: true })
    const result = await retryFailedRunAction(store as never, failure())
    expect(result).toBe(false)
    // 会话切换后不写任何反馈/错误到新会话
    expect(store.feedback).toBeNull()
  })
})
