// 文件名:globalAssistantSuccessorHandoff.spec.ts
// 用途:全局助手 steer 后继运行交接的回归测试(BUG-01):验证后端 AFTER_COMMIT 交接
//       存在时间窗时,store 会持续轮询活动状态直到后继运行出现,不提前丢失 steer 状态。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import {
  useGlobalAssistantStore,
  type GaTerminal,
} from '@/features/global-assistant/state/globalAssistantStore'
import type { GaEventEnvelope } from '@/features/global-assistant/api/globalAssistant'

/**
 * BUG-01 回归测试:全局助手 steer 后继运行的观测。
 *
 * 后端的交接是"合法但迟到":`TurnHandoffListener` 在 AFTER_COMMIT 阶段创建
 * 后继运行,因此 `GET /threads/{id}/activity` 会在一小段时间窗口内先返回
 * `{ activeRun: null, pendingSteer: S }`,之后才可见 `{ activeRun: B, pendingSteer: null }`。
 * store 必须持续观测规范的活动状态,直到交接落定。
 */

const THREAD = 't-1'
const RUN_A = 'r-A'
const RUN_B = 'r-B'
const STEER = 's-1'
const STEERING_STATUS = '正在调整方向…'

/** 必须与 globalAssistantStore 中的 GA_SUCCESSOR_OBSERVE_INTERVAL_MS 保持一致。 */
const OBSERVE_INTERVAL_MS = 400

interface ActivityBody {
  activeRun: { runId: string; status: string } | null
  pendingSteer: {
    steerId: string
    message: string
    status: string
    interruptedRunId: string
    successorRunId: string | null
    createdAt: string
  } | null
}

function pendingOnly(steerId = STEER): ActivityBody {
  return {
    activeRun: null,
    pendingSteer: {
      steerId,
      message: '换方向',
      status: 'PENDING',
      interruptedRunId: RUN_A,
      successorRunId: null,
      createdAt: '2026-09-10T00:00:00Z',
    },
  }
}

function withSuccessor(runId = RUN_B, status = 'RUNNING'): ActivityBody {
  return { activeRun: { runId, status }, pendingSteer: null }
}

function resolvedWithoutSuccessor(): ActivityBody {
  return { activeRun: null, pendingSteer: null }
}

function jsonOk(body: unknown, status = 200): Response {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as unknown as Response
}

/** 一个保持打开的 fetch 流 body:没有终止帧,也没有重连路径。 */
function openStreamBody(): unknown {
  return {
    getReader: () => ({
      read: () => new Promise<ReadableStreamReadResult<Uint8Array>>(() => {}),
      releaseLock: () => {},
      cancel: () => Promise.resolve(),
    }),
  }
}

interface Harness {
  activityCalls: () => number
  streamOpens: () => string[]
  eventReplays: () => string[]
  /** 解决一次先前被挂起的活动读取(用于过期结果测试)。 */
  deferActivity: (body: ActivityBody) => void
  /** 从这里开始替换脚本化的活动响应。 */
  setActivity: (body: ActivityBody) => void
}

interface HarnessOptions {
  /** 脚本化的活动响应;最后一条会永远重复。 */
  activity: ActivityBody[]
  events?: Record<string, GaEventEnvelope[]>
  /** 前 N 次活动读取等待手动解决。 */
  deferredActivityCount?: number
}

function installHarness(options: HarnessOptions): Harness {
  let activityCalls = 0
  const streamOpens: string[] = []
  const eventReplays: string[] = []
  const deferred: Array<(body: ActivityBody) => void> = []
  let deferredRemaining = options.deferredActivityCount ?? 0
  let override: ActivityBody | null = null

  const fetchMock = vi.fn(async (input: unknown, init?: RequestInit) => {
    const url = String(input)
    const headers = (init?.headers ?? {}) as Record<string, string>
    const accept = String(headers.Accept ?? '')
    if (url.includes('/activity')) {
      activityCalls += 1
      if (deferredRemaining > 0) {
        deferredRemaining -= 1
        return jsonOk(await new Promise<ActivityBody>((resolve) => { deferred.push(resolve) }))
      }
      if (override) return jsonOk(override)
      const script = options.activity
      return jsonOk(script[Math.min(activityCalls - 1, script.length - 1)])
    }
    const eventsMatch = url.match(/\/runs\/([^/?]+)\/events/)
    if (eventsMatch) {
      const runId = eventsMatch[1]
      if (accept.includes('text/event-stream')) {
        streamOpens.push(runId)
        return { ok: true, status: 200, body: openStreamBody() } as unknown as Response
      }
      eventReplays.push(runId)
      return jsonOk(options.events?.[runId] ?? [])
    }
    if (url.includes('/messages')) return jsonOk([])
    if (/\/runs\/[^/?]+$/.test(url)) {
      return jsonOk({
        runId: RUN_B,
        threadId: THREAD,
        status: 'RUNNING',
        stepCount: 0,
        cancelRequestedAt: null,
        startedAt: '2026-09-10T00:00:00Z',
        completedAt: null,
        errorCode: null,
      })
    }
    return jsonOk([])
  })

  vi.stubGlobal('fetch', fetchMock)
  return {
    activityCalls: () => activityCalls,
    streamOpens: () => streamOpens,
    eventReplays: () => eventReplays,
    deferActivity: (body) => {
      const next = deferred.shift()
      if (next) next(body)
    },
    setActivity: (body) => { override = body },
  }
}

function event(runId: string, sequence: number, type: string, payload: Record<string, unknown> = {}): GaEventEnvelope {
  return {
    eventId: 'e-' + runId + '-' + sequence,
    runId,
    threadId: THREAD,
    type: type as GaEventEnvelope['type'],
    sequence,
    createdAt: '2026-09-10T00:00:0' + sequence + 'Z',
    payload,
  }
}

const CANCELLED: GaTerminal = { type: 'RUN_CANCELLED', errorCode: null, reason: null }

let current: ReturnType<typeof useGlobalAssistantStore> | null = null

/** 构造一个 store:正在观测已终态的运行 A,且存在一条已接受但未落定的 steer S。 */
function seedStore(): ReturnType<typeof useGlobalAssistantStore> {
  const store = useGlobalAssistantStore()
  store.threadId = THREAD
  store.activeRunId = RUN_A
  store.activeStatus = 'RUNNING'
  store.lastSequence = 7
  store.pendingSteer = {
    id: STEER,
    message: '换方向',
    status: 'PENDING',
    createdAt: '2026-09-10T00:00:00Z',
    optimisticId: null,
  }
  current = store
  return store
}

/** 冲刷待处理的微任务,但不推进假时钟。 */
async function settle(): Promise<void> {
  await vi.advanceTimersByTimeAsync(0)
}

/** 恰好推进一个后继观测间隔。 */
async function tick(): Promise<void> {
  await vi.advanceTimersByTimeAsync(OBSERVE_INTERVAL_MS)
}

describe('global assistant successor handoff observation (BUG-01)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.useFakeTimers()
    current = null
  })

  afterEach(() => {
    try { current?.$resetForTest() } catch { /* store already torn down */ }
    current = null
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('finds the successor after the first activity read only reports a pending steer', async () => {
    const h = installHarness({
      activity: [pendingOnly(), withSuccessor()],
      events: { [RUN_B]: [event(RUN_B, 1, 'STATUS', { message: '正在处理新方向' })] },
    })
    const store = seedStore()

    store.finishTerminal(CANCELLED)
    await settle()

    // 后端合法的中间态:还没有活跃运行,steer 仍待生效。
    expect(store.activeRunId).toBeNull()
    expect(store.currentStatus).toBe(STEERING_STATUS)
    expect(h.activityCalls()).toBe(1)

    // 观测必须持续到交接真正落定。
    await tick()

    expect(store.activeRunId).toBe(RUN_B)
    expect(store.activeStatus).toBe('RUNNING')
    expect(store.pendingSteer).toBeNull()
    expect(store.lastSequence).toBe(1)
    expect(store.currentStatus).toBe('正在处理新方向')
    expect(h.eventReplays()).toContain(RUN_B)
    expect(h.streamOpens()).toEqual([RUN_B])
  })

  it('attaches the successor exactly once when it is already visible on the first read', async () => {
    const h = installHarness({
      activity: [withSuccessor()],
      events: { [RUN_B]: [event(RUN_B, 1, 'STATUS', { message: '正在处理新方向' })] },
    })
    const store = seedStore()

    store.finishTerminal(CANCELLED)
    await settle()

    expect(store.activeRunId).toBe(RUN_B)
    expect(store.activeStatus).toBe('RUNNING')
    expect(store.lastSequence).toBe(1)
    expect(h.activityCalls()).toBe(1)
    expect(h.eventReplays()).toEqual([RUN_B])
    expect(h.streamOpens()).toEqual([RUN_B])
  })

  it('keeps observing across several unresolved reads before the successor appears', async () => {
    const h = installHarness({
      activity: [pendingOnly(), pendingOnly(), withSuccessor()],
    })
    const store = seedStore()

    store.finishTerminal(CANCELLED)
    await settle()
    expect(h.activityCalls()).toBe(1)
    expect(store.activeRunId).toBeNull()

    await tick()
    expect(h.activityCalls()).toBe(2)
    expect(store.activeRunId).toBeNull()
    expect(store.currentStatus).toBe(STEERING_STATUS)

    await tick()
    expect(h.activityCalls()).toBe(3)
    expect(store.activeRunId).toBe(RUN_B)
    expect(h.streamOpens()).toEqual([RUN_B])
  })

  it('never runs two parallel observation loops for the same thread', async () => {
    const h = installHarness({ activity: [pendingOnly()] })
    const store = seedStore()

    store.finishTerminal(CANCELLED)
    await settle()
    expect(h.activityCalls()).toBe(1)

    // 重复触发后继跟进绝不能再孵化出独立的重试链。
    await store.pollSuccessorAfterTerminal()
    await store.pollSuccessorAfterTerminal()
    await settle()
    const base = h.activityCalls()
    expect(base).toBe(3)

    await tick()
    expect(h.activityCalls()).toBe(base + 1)

    await tick()
    expect(h.activityCalls()).toBe(base + 2)
  })

  it('ignores a stale activity result that resolves after a thread switch', async () => {
    const h = installHarness({
      activity: [pendingOnly()],
      deferredActivityCount: 1,
    })
    const store = seedStore()

    store.finishTerminal(CANCELLED)
    await settle()
    expect(h.activityCalls()).toBe(1)

    await store.switchThread('t-2')
    expect(store.threadId).toBe('t-2')

    // T1 的在途读取现在返回的是 T1 的后继:必须被丢弃。
    h.deferActivity(withSuccessor('r-B1'))
    await settle()
    await tick()
    await tick()

    expect(store.threadId).toBe('t-2')
    expect(store.activeRunId).toBeNull()
    expect(h.streamOpens()).toEqual([])
    expect(h.eventReplays()).toEqual([])
  })

  it('stops observing when the user stops the thread', async () => {
    const h = installHarness({ activity: [pendingOnly(), withSuccessor()] })
    const store = seedStore()

    store.finishTerminal(CANCELLED)
    await settle()
    expect(store.currentStatus).toBe(STEERING_STATUS)

    await store.cancelActiveRun()
    const callsAtStop = h.activityCalls()
    await tick()
    await tick()

    expect(h.activityCalls()).toBe(callsAtStop)
    expect(store.activeRunId).toBeNull()
    expect(h.streamOpens()).toEqual([])
    expect(h.eventReplays()).toEqual([])
  })

  it('exits observation when the pending steer disappears without a successor', async () => {
    const h = installHarness({
      activity: [pendingOnly(), resolvedWithoutSuccessor()],
    })
    const store = seedStore()

    store.finishTerminal(CANCELLED)
    await settle()
    expect(store.currentStatus).toBe(STEERING_STATUS)

    await tick()

    expect(store.activeRunId).toBeNull()
    expect(store.pendingSteer).toBeNull()
    expect(store.currentStatus).toBeNull()

    const settled = h.activityCalls()
    await tick()
    expect(h.activityCalls()).toBe(settled)
  })

  it('continues observing after the fast observation window while the steer is still pending', async () => {
    const h = installHarness({ activity: [pendingOnly()] })
    const store = seedStore()

    store.finishTerminal(CANCELLED)
    await settle()
    expect(store.currentStatus).toBe(STEERING_STATUS)
    expect(h.activityCalls()).toBe(1)

    // 远超旧的 400ms x 15 固定预算:仍待生效的 steer 必须让观测保持存活
    // (降级为慢速轮询,绝不停止)。
    for (let i = 0; i < 30; i += 1) await vi.advanceTimersByTimeAsync(2000)
    expect(store.currentStatus).toBe(STEERING_STATUS)
    expect(store.activeRunId).toBeNull()
    expect(h.activityCalls()).toBeGreaterThan(1)

    // 后继运行很久之后才出现:观测仍必须捕捉到它。
    h.setActivity(withSuccessor())
    await vi.advanceTimersByTimeAsync(2000)

    expect(store.activeRunId).toBe(RUN_B)
    expect(store.activeStatus).toBe('RUNNING')
    expect(store.pendingSteer).toBeNull()
    expect(h.streamOpens()).toEqual([RUN_B])
  })

  it('ignores an in-flight successor activity result that returns after stop', async () => {
    const h = installHarness({
      activity: [pendingOnly()],
      deferredActivityCount: 1,
      events: { [RUN_B]: [event(RUN_B, 1, 'STATUS', { message: '正在处理新方向' })] },
    })
    const store = seedStore()

    store.finishTerminal(CANCELLED)
    await settle()
    expect(h.activityCalls()).toBe(1)

    // 第一次后继读取仍在途时,停止操作已完成。
    await store.cancelActiveRun()
    expect(store.successorObserverToken).toBe(0)

    h.deferActivity(withSuccessor())
    await settle()
    await tick()
    await tick()

    expect(store.activeRunId).toBeNull()
    // 'CANCELLED' 是 A 自己的终态;绝不能把它复用到 B 身上。
    expect(store.activeStatus).not.toBe('RUNNING')
    expect(store.isRunning).toBe(false)
    expect(store.pendingSteer).toBeNull()
    expect(store.currentStatus).not.toBe(STEERING_STATUS)
    expect(h.eventReplays()).toEqual([])
    expect(h.streamOpens()).toEqual([])
    expect(h.activityCalls()).toBe(1)
  })

  it('does not replay or stream the same successor twice', async () => {
    const h = installHarness({
      activity: [withSuccessor()],
      events: { [RUN_B]: [event(RUN_B, 1, 'STATUS', { message: '正在处理新方向' })] },
    })
    const store = seedStore()

    store.finishTerminal(CANCELLED)
    await settle()
    expect(h.eventReplays()).toEqual([RUN_B])
    expect(h.streamOpens()).toEqual([RUN_B])

    // 通过公开的后继跟进入口再次观测同一个后继运行。
    await store.attachSuccessorIfReady()
    await store.attachSuccessorIfReady()
    await settle()

    expect(h.eventReplays()).toEqual([RUN_B])
    expect(h.streamOpens()).toEqual([RUN_B])
    expect(store.activeRunId).toBe(RUN_B)
    expect(store.lastSequence).toBe(1)
  })
})
