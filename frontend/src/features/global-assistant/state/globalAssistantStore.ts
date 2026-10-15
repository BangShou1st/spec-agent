// 文件名:globalAssistantStore.ts
// 用途:全局助手的核心 Pinia 状态仓:线程/消息/运行的生命周期管理、SSE 事件流接入与
//       逐事件投影(GaRunProjection)、steer 转向与后继运行交接观测(BUG-01)、
//       断线重连、乐观消息与草稿、会话历史与删除,以及 thread/run/panel 的本地持久化。

import { defineStore } from 'pinia'
import { safeWebUrl } from '../presentation/webSources'
import { readStored, writeStored } from '@/shared/lib/safeStorage'
import { ApiError } from '@/shared/http/client'
import {
  createGaRun,
  createGaThread,
  deleteGaThread,
  getGaRun,
  getGaThread,
  getGaThreadActivity,
  listGaEvents,
  listGaMessages,
  listGaThreads,
  steerGaRun,
  stopGaThread,
  gaUiActionToRoute,
  type GaEventEnvelope,
  type GaMessage,
  type GaRun,
  type GaThreadActivity,
  type GaThreadListItem,
  type GaUiContext,
} from '@/features/global-assistant/api/globalAssistant'
import { openGaEventStream } from '@/features/global-assistant/api/globalAssistantEvents'
import { gaErrorMessage, gaToolDisplayName, gaArgsSummary, gaStatusMessage, GA_SENDING_STATUS, GA_GENERATING_STATUS } from '@/features/global-assistant/presentation/globalAssistantPresentation'

export const GA_THREAD_KEY = 'spec-agent:global-assistant:thread:v1'
export const GA_RUN_KEY = 'spec-agent:global-assistant:run:v1'
export const GA_PANEL_KEY = 'spec-agent:global-assistant:panel:v1'
export const GA_IDENTITY_KEY = 'spec-agent:global-assistant:owner:v1'
class GaIdentityExpiredError extends Error {}
const identityStoreRoots = new WeakMap<object, object>()
/** A promise can settle before the awaiting continuation runs. Guard the
 * continuation's actual store access as well as the awaited result. */
function identityScopedStore<T extends { identityEpoch: number }>(store: T, epoch: number): T {
  const root = (identityStoreRoots.get(store) ?? store) as T
  const scoped = new Proxy(root, {
    get(target, key) {
      if (target.identityEpoch !== epoch) throw new GaIdentityExpiredError()
      return Reflect.get(target, key, target)
    },
    set(target, key, value) {
      if (target.identityEpoch !== epoch) throw new GaIdentityExpiredError()
      return Reflect.set(target, key, value, target)
    },
  })
  identityStoreRoots.set(scoped, root)
  return scoped
}

export type GaToolState = 'running' | 'success' | 'failure' | 'interrupted'

export interface GaResourceRef {
  kind: string
  id: string
  label: string
  metadata?: Record<string, unknown> | null
}

const GA_UUID_RE = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/

/** steer 交接待生效、后继运行尚未可见时展示的状态文案。 */
const GA_STEERING_STATUS = '正在调整方向…'

/**
 * BUG-01:后端在 AFTER_COMMIT 阶段才创建 steer 的后继运行,因此线程的规范活动状态
 * 会在一段未知长度的时间窗口内合法地返回 `{ activeRun: null, pendingSteer: S }`。
 * store 必须持续观测活动状态直到交接落定,而不是读一次就放弃。
 *
 * 观测的生命周期由规范状态驱动,绝不靠超时截止:先快速读若干次,
 * 之后在待生效 steer 仍未解决时转为持续的低频轮询。
 * 只有规范状态落定或生命周期事件才能结束观测。
 */
export const GA_SUCCESSOR_OBSERVE_INTERVAL_MS = 400
export const GA_SUCCESSOR_OBSERVE_FAST_READS = 10
export const GA_SUCCESSOR_OBSERVE_SLOW_INTERVAL_MS = 1500

/** 按码点的有界截断:用 Array.from 按码点切分,绝不按 UTF-16 单元切分。 */
function truncateGaLabel(value: string, max = 200): string {
  const points = Array.from(value)
  return points.length > max ? points.slice(0, max).join('') : value
}

export function sanitizeGaResourceRefs(raw: unknown): GaResourceRef[] {
  if (!Array.isArray(raw)) return []
  const out: GaResourceRef[] = []
  for (const item of raw) {
    if (!item || typeof item !== 'object') continue
    const m = item as Record<string, unknown>
    if (m.kind !== 'PROJECT' && m.kind !== 'SOURCE' && m.kind !== 'WEB_SOURCE') continue
    if (typeof m.id !== 'string' || !GA_UUID_RE.test(m.id)) continue
    if (typeof m.label !== 'string' || !m.label) continue
    const label = truncateGaLabel(m.label)
    let metadata: Record<string, unknown> | null = null
    if (m.metadata && typeof m.metadata === 'object') {
      const mm = m.metadata as Record<string, unknown>
      if (m.kind === 'PROJECT' && typeof mm.updatedAt === 'string') metadata = { updatedAt: mm.updatedAt.slice(0, 64) }
      if (m.kind === 'SOURCE') {
        if (typeof mm.sourceRef !== 'string' || typeof mm.sourceVersion !== 'string' || typeof mm.contentHash !== 'string' || !/^[0-9a-f]{64}$/.test(mm.contentHash)) continue
        metadata = { sourceRef: mm.sourceRef.slice(0, 240), sourceVersion: mm.sourceVersion.slice(0, 128), contentHash: mm.contentHash }
        if (typeof mm.excerpt === 'string') metadata.excerpt = truncateGaLabel(mm.excerpt, 800)
        if (typeof mm.projectId === 'string' && GA_UUID_RE.test(mm.projectId)) metadata.projectId = mm.projectId
        for (const key of ['startOffset', 'endOffset']) if (typeof mm[key] === 'number' && Number.isSafeInteger(mm[key]) && (mm[key] as number) >= 0) metadata[key] = mm[key]
      }
      if (m.kind === 'WEB_SOURCE') {
        const url = safeWebUrl(mm.url)
        if (!url || mm.sourceRef !== 'web:' + m.id || typeof mm.fetchedAt !== 'string'
          || !['SEARCH_SNIPPET', 'EXTRACTED_TEXT'].includes(String(mm.contentStage)) || mm.authority !== 'EXTERNAL_EVIDENCE') continue
        metadata = { url, sourceRef: mm.sourceRef, fetchedAt: mm.fetchedAt.slice(0, 64), contentStage: mm.contentStage,
          authority: mm.authority, truncated: mm.truncated === true, excerpt: typeof mm.excerpt === 'string' ? truncateGaLabel(mm.excerpt, 800) : '' }
      }
    }
    if ((m.kind === 'SOURCE' || m.kind === 'WEB_SOURCE') && !metadata) continue
    if (out.some(r => r.kind === m.kind && r.id === m.id)) continue
    out.push({ kind: m.kind, id: m.id, label, metadata })
    if (out.length >= 10) break
  }
  return out
}

export interface GaToolActivity {
  runId?: string
  toolCallId?: string | null
  sequence?: number
  key: string
  capabilityId: string
  displayName: string
  state: GaToolState
  summary: string | null
  argsSummary: string | null
  startedAt: string
  endedAt: string | null
  durationMs: number | null
  resourceRefs: GaResourceRef[]
  /** TOOL_COMPLETED 事件给出的结构化结果类型(用于通用渲染)。 */
  resultKind: string | null
  /** 可信的结果条数:清洗后的 resourceRefs 长度优先,否则用事件里的 resultCount。 */
  resultCount: number | null
}

function sanitizeResultCount(raw: unknown): number | null {
  if (typeof raw !== 'number' || !Number.isInteger(raw) || raw < 0 || raw > 10000) return null
  return raw
}

export interface GaTerminal {
  type: 'RUN_COMPLETED' | 'RUN_FAILED' | 'RUN_CANCELLED'
  errorCode: string | null
  reason: string | null
}

/** 确定性的单运行事件投影。纯逻辑,可单测。 */
export class GaRunProjection {
  lastSequence = 0
  streamingText = ''
  streamGeneration: number | null = null
  currentStatus: string | null = null
  activities: GaToolActivity[] = []
  waitingQuestion: string | null = null
  approvalRequired = false
  uiAction: { destination: string; resourceId: string | null } | null = null
  terminal: GaTerminal | null = null
  private toolSeq = 0
  private runId: string | null = null

  /** 事件产生了可见变化时返回 true。 */
  apply(event: GaEventEnvelope): boolean {
    if (!event || typeof event.sequence !== 'number') return false
    if (this.runId !== null && event.runId !== this.runId) return false
    this.runId = event.runId
    if (event.sequence <= this.lastSequence) return false
    // The terminal projection is immutable even if a reconnect delivers late frames.
    if (this.terminal !== null) return false
    this.lastSequence = event.sequence
    const payload = (event.payload ?? {}) as Record<string, unknown>
    switch (event.type) {
      case 'RUN_STARTED':
        return false
      case 'STATUS': {
        const message = typeof payload.message === 'string' ? payload.message : ''
        if (!message) return false
        this.currentStatus = gaStatusMessage(message)
        return true
      }
      case 'ASSISTANT_DELTA': {
        // 旧式一次性消息事件(历史持久化的运行、失败文案、mock 流)。
        // 新运行使用带 generation 的 ANSWER_STREAM_* 事件。
        const text = typeof payload.text === 'string' ? payload.text : ''
        if (!text) return false
        this.streamingText += text
        return true
      }
      case 'ANSWER_STREAM_STARTED': {
        const generation = typeof payload.generation === 'number' ? payload.generation : null
        if (generation === null) return false
        if (this.streamGeneration === generation) return false
        this.streamGeneration = generation
        this.streamingText = ''
        return true
      }
      case 'ANSWER_DELTA': {
        const text = typeof payload.text === 'string' ? payload.text : ''
        if (!text) return false
        const generation = typeof payload.generation === 'number' ? payload.generation : null
        if (generation !== null && generation !== this.streamGeneration) {
          // 没有显式 STARTED 就出现的新 generation(例如重新订阅的快照):
          // 用替换旧草稿的方式对账。
          this.streamGeneration = generation
          this.streamingText = text
          return true
        }
        this.streamingText += text
        return true
      }
      case 'ANSWER_STREAM_RESET': {
        const generation = typeof payload.generation === 'number' ? payload.generation : null
        if (generation === null) return false
        this.streamGeneration = generation
        this.streamingText = ''
        return true
      }
      case 'ASSISTANT_COMPLETED':
        return false
      case 'TOOL_STARTED': {
        const capabilityId = typeof payload.capabilityId === 'string' ? payload.capabilityId : 'unknown'
        this.toolSeq += 1
        this.activities.push({
          runId: event.runId,
          toolCallId: typeof payload.toolCallId === 'string' ? payload.toolCallId : null,
          sequence: event.sequence,
          key: capabilityId + '#' + this.toolSeq + '#' + event.sequence,
          capabilityId,
          displayName: gaToolDisplayName(capabilityId),
          state: 'running',
          summary: null,
          argsSummary: gaArgsSummary(payload.arguments),
          startedAt: typeof event.createdAt === 'string' ? event.createdAt : new Date().toISOString(),
          endedAt: null,
          durationMs: null,
          resourceRefs: [],
          resultKind: null,
          resultCount: null,
        })
        return true
      }
      case 'TOOL_COMPLETED': {
        const capabilityId = typeof payload.capabilityId === 'string' ? payload.capabilityId : 'unknown'
        const summary = typeof payload.summary === 'string' ? payload.summary : null
        const resourceRefs = sanitizeGaResourceRefs(payload.resourceRefs)
        const resultKind = typeof payload.resultKind === 'string' ? payload.resultKind : null
        const eventCount = sanitizeResultCount(payload.resultCount)
        const projects = resourceRefs.filter(r => r.kind === 'PROJECT')
        const resultCount = projects.length > 0 ? projects.length : eventCount
        const target = findRunningActivity(this.activities, capabilityId, event.runId, payload.toolCallId)
        if (target) {
          target.state = 'success'
          target.summary = summary
          target.resourceRefs = resourceRefs
          target.resultKind = resultKind
          target.resultCount = resultCount
          target.endedAt = typeof event.createdAt === 'string' ? event.createdAt : target.endedAt
          target.durationMs = diffMs(target.startedAt, target.endedAt)
          return true
        }
        this.toolSeq += 1
        this.activities.push({
          runId: event.runId,
          toolCallId: typeof payload.toolCallId === 'string' ? payload.toolCallId : null,
          sequence: event.sequence,
          key: capabilityId + '#' + this.toolSeq + '#' + event.sequence,
          capabilityId,
          displayName: gaToolDisplayName(capabilityId),
          state: 'success',
          summary,
          argsSummary: null,
          startedAt: typeof event.createdAt === 'string' ? event.createdAt : new Date().toISOString(),
          endedAt: typeof event.createdAt === 'string' ? event.createdAt : null,
          durationMs: null,
          resourceRefs,
          resultKind,
          resultCount,
        })
        return true
      }
      case 'TOOL_FAILED': {
        const capabilityId = typeof payload.capabilityId === 'string' ? payload.capabilityId : 'unknown'
        const errorCode = typeof payload.errorCode === 'string' ? payload.errorCode : null
        const reason = typeof payload.reason === 'string' ? payload.reason : null
        const summary = errorCode ? gaErrorMessage(errorCode, reason ?? undefined) : (reason ?? '工具执行失败，请稍后再试')
        const target = findRunningActivity(this.activities, capabilityId, event.runId, payload.toolCallId)
        if (target) {
          target.state = 'failure'
          target.summary = summary
          target.endedAt = typeof event.createdAt === 'string' ? event.createdAt : target.endedAt
          target.durationMs = diffMs(target.startedAt, target.endedAt)
          return true
        }
        this.toolSeq += 1
        this.activities.push({
          runId: event.runId,
          toolCallId: typeof payload.toolCallId === 'string' ? payload.toolCallId : null,
          sequence: event.sequence,
          key: capabilityId + '#' + this.toolSeq + '#' + event.sequence,
          capabilityId,
          displayName: gaToolDisplayName(capabilityId),
          state: 'failure',
          summary,
          argsSummary: null,
          startedAt: typeof event.createdAt === 'string' ? event.createdAt : new Date().toISOString(),
          endedAt: typeof event.createdAt === 'string' ? event.createdAt : null,
          durationMs: null,
          resourceRefs: [],
          resultKind: null,
          resultCount: null,
        })
        return true
      }
      case 'USER_INPUT_REQUIRED': {
        const question = typeof payload.question === 'string' ? payload.question : ''
        if (!question) return false
        this.waitingQuestion = question
        this.currentStatus = null
        return true
      }
      case 'APPROVAL_REQUIRED':
        // V1 没有审批应答端点:只做克制的非交互状态提示。
        this.approvalRequired = true
        return true
      case 'UI_ACTION': {
        const destination = typeof payload.destination === 'string' ? payload.destination : ''
        if (!destination) return false
        const resourceId = typeof payload.resourceId === 'string' ? payload.resourceId : null
        this.uiAction = { destination, resourceId }
        return true
      }
      case 'RUN_COMPLETED':
        this.interruptActivities(event)
        this.terminal = { type: 'RUN_COMPLETED', errorCode: null, reason: null }
        this.currentStatus = null
        return true
      case 'RUN_CANCELLED':
        this.interruptActivities(event)
        this.terminal = { type: 'RUN_CANCELLED', errorCode: null, reason: null }
        this.currentStatus = null
        this.streamingText = ''
        return true
      case 'RUN_FAILED': {
        const errorCode = typeof payload.errorCode === 'string' ? payload.errorCode : null
        const reason = typeof payload.reason === 'string' ? payload.reason : null
        this.interruptActivities(event)
        this.terminal = { type: 'RUN_FAILED', errorCode, reason }
        this.currentStatus = null
        this.streamingText = ''
        return true
      }
      default:
        return false
    }
  }
  private interruptActivities(event: GaEventEnvelope): void {
    for (const item of this.activities) {
      if (item.state !== 'running' || (item.runId && item.runId !== event.runId)) continue
      item.state = 'interrupted'
      item.summary = event.type === 'RUN_CANCELLED' ? '本轮已取消，未收到工具完成确认' : '本轮已结束，未收到工具完成确认'
      item.endedAt = event.createdAt
      item.durationMs = diffMs(item.startedAt, item.endedAt)
    }
  }
}

function findRunningActivity(list: GaToolActivity[], capabilityId: string, runId: string, toolCallId: unknown): GaToolActivity | null {
  const candidates = list.filter(item => item.capabilityId === capabilityId && item.state === 'running'
    && (!item.runId || item.runId === runId)
    && (typeof toolCallId === 'string' ? item.toolCallId === toolCallId : !item.toolCallId))
  // Historical events without IDs are only associated when there is one unambiguous start.
  return candidates.length === 1 ? candidates[0]! : null
}

function diffMs(startedAt: string, endedAt: string | null): number | null {
  if (!endedAt) return null
  const start = Date.parse(startedAt)
  const end = Date.parse(endedAt)
  if (Number.isNaN(start) || Number.isNaN(end)) return null
  const diff = end - start
  return diff >= 0 ? Math.round(diff) : null
}

export const useGlobalAssistantStore = defineStore('globalAssistant', {
  state: () => ({
    identityEpoch: 0,
    privateIdentity: null as string | null,
    threadId: null as string | null,
    messages: [] as GaMessage[],
    loadingThread: false,
    sending: false,
    draft: '',
    activeRunId: null as string | null,
    activeStatus: null as string | null,
    lastSequence: 0,
    streamingText: '',
    streamGeneration: null as number | null,
    activities: [] as GaToolActivity[],
    historicalActivities: {} as Record<string, GaToolActivity[]>,
    historyLoadedRuns: [] as string[],
    currentStatus: null as string | null,
    sendStartedAt: null as number | null,
    firstVisibleUiMs: null as number | null,
    firstToolResultMs: null as number | null,
    waitingQuestion: null as string | null,
    approvalRequired: false,
    pendingNavigation: null as string | null,
    lastUiAction: null as { destination: string; resourceId: string | null } | null,
    cancelRequested: false,
    connection: 'idle' as 'idle' | 'connecting' | 'connected' | 'reconnecting' | 'disconnected',
    error: null as { code: string; message: string } | null,
    initialized: false,
    panelOpen: false,
    reconnectAttempts: 0,
    threads: [] as GaThreadListItem[],
    threadsLoading: false,
    threadsError: null as string | null,
    historyOpen: false,
    switchingThread: false,
    pendingSteer: null as { id: string; message: string; status: string; createdAt: string; optimisticId: string | null } | null,
    steerSending: false as boolean,
    stoppedNotice: false as boolean,
    deletingThreadId: null as string | null,
    confirmDeleteThreadId: null as string | null,
    /** 后继观测循环持有该线程期间非零(单例令牌)。 */
    successorObserverToken: 0 as number,
    /** 最近一次已挂接的后继运行 id;保证挂接操作幂等。 */
    successorAttachedRunId: null as string | null,
  }),
  getters: {
    timelineActivities(state): GaToolActivity[] {
      return [...Object.entries(state.historicalActivities)
        .filter(([run]) => state.messages.some(m => m.runId === run) && run !== state.activeRunId && !state.activities.some(a => a.runId === run))
        .flatMap(([, items]) => items), ...state.activities]
    },
    isRunning(state): boolean {
      return state.activeRunId !== null && (state.activeStatus === 'CREATED' || state.activeStatus === 'RUNNING')
    },
    hasMessages(state): boolean {
      return state.messages.length > 0 || state.streamingText.length > 0 || state.activities.length > 0
    },
    canSwitchThread(state): boolean {
      return state.activeRunId === null || !((state.activeStatus === 'CREATED' || state.activeStatus === 'RUNNING'))
    },
  },
  actions: {
    bindIdentity(identity: string | null): void {
      if (identity === this.privateIdentity && identity !== null) return
      const nextEpoch = this.identityEpoch + 1
      this.stopSuccessorObservation()
      this.closeStream()
      cancelReconnectWaits()
      this.$reset()
      this.identityEpoch = nextEpoch
      this.privateIdentity = identity
      if (identity === null || readStored(GA_IDENTITY_KEY) !== identity) {
        writeStored(GA_THREAD_KEY, null)
        writeStored(GA_RUN_KEY, null)
      }
      writeStored(GA_IDENTITY_KEY, identity)
    },
    async awaitIdentity<T>(epoch: number, work: Promise<T>): Promise<T> {
      const result = await work
      if (this.identityEpoch !== epoch) throw new GaIdentityExpiredError()
      return result
    },
    initPanel(): void {
      const stored = readStored(GA_PANEL_KEY)
      this.panelOpen = stored === 'open'
    },
    setPanelOpen(open: boolean): void {
      this.panelOpen = open
      writeStored(GA_PANEL_KEY, open ? 'open' : 'closed')
    },
    togglePanel(): void {
      this.setPanelOpen(!this.panelOpen)
    },
    consumeNavigation(): string | null {
      const target = this.pendingNavigation
      this.pendingNavigation = null
      return target
    },
    async init(): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      if (identityStore.initialized) return
      identityStore.initialized = true
      identityStore.initPanel()
      const storedThread = readStored(GA_THREAD_KEY)
      const storedRun = readStored(GA_RUN_KEY)
      if (!storedThread) {
        void identityStore.loadThreads()
        return
      }
      identityStore.loadingThread = true
      try {
        await identityStore.awaitIdentity(identityEpoch, (getGaThread(storedThread)))
        identityStore.threadId = storedThread
        identityStore.messages = await identityStore.awaitIdentity(identityEpoch, (listGaMessages(storedThread)))
        await identityStore.awaitIdentity(identityEpoch, (identityStore.restoreProcessHistory()))
        await identityStore.awaitIdentity(identityEpoch, (identityStore.refreshActivity()))
        if (identityStore.activeRunId) {
          try {
            const envelopes = await identityStore.awaitIdentity(identityEpoch, (listGaEvents(identityStore.activeRunId)))
            for (const envelope of envelopes) identityStore.ingestEvent(envelope)
          } catch {
        if (this.identityEpoch !== identityEpoch) throw new GaIdentityExpiredError()
 /* 重放尽力而为,SSE 会补齐 */ }
          if (identityStore.activeRunId) identityStore.openStream()
        } else if (storedRun && !identityStore.pendingSteer) {
          await identityStore.awaitIdentity(identityEpoch, (identityStore.recoverRun(storedRun)))
        }
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new GaIdentityExpiredError()

        if (err instanceof ApiError && err.code === 'THREAD_NOT_FOUND') {
          writeStored(GA_THREAD_KEY, null)
          writeStored(GA_RUN_KEY, null)
          identityStore.threadId = null
          identityStore.messages = []
          identityStore.activeRunId = null
          identityStore.pendingSteer = null
        } else if (err instanceof ApiError) {
          identityStore.threadId = storedThread
          try { identityStore.messages = await identityStore.awaitIdentity(identityEpoch, (listGaMessages(storedThread))) } catch {
        if (this.identityEpoch !== identityEpoch) throw new GaIdentityExpiredError()
 /* 保持为空 */ }
          identityStore.error = { code: err.code, message: gaErrorMessage(err.code, err.message) }
        }
      } finally {
        if (this.identityEpoch === identityEpoch) {
        identityStore.loadingThread = false
      
        }
      }
      void identityStore.loadThreads()
    
      } catch (identityError) {
        if (identityError instanceof GaIdentityExpiredError) { return }
        throw identityError
      }
    },
    async refreshActivity(): Promise<GaThreadActivity | null> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      const act = await identityStore.awaitIdentity(identityEpoch, (identityStore.readThreadActivity()))
      if (!act) return null
      identityStore.applyThreadActivity(act)
      return act
    
      } catch (identityError) {
        if (identityError instanceof GaIdentityExpiredError) { return null }
        throw identityError
      }
    },
    /**
     * 带线程守护的规范读取。刻意不参与投影,
     * 让调用方能在任何内容写入 state 之前先校验归属。
     */
    async readThreadActivity(): Promise<GaThreadActivity | null> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      if (!identityStore.threadId) return null
      const requestedThreadId = identityStore.threadId
      try {
        const act = await identityStore.awaitIdentity(identityEpoch, (getGaThreadActivity(identityStore.threadId)))
        // 线程切换之后才返回的读取属于旧线程。
        if (identityStore.threadId !== requestedThreadId) return null
        return act
      } catch {
        if (this.identityEpoch !== identityEpoch) throw new GaIdentityExpiredError()

        return null
      }
    
      } catch (identityError) {
        if (identityError instanceof GaIdentityExpiredError) { return null }
        throw identityError
      }
    },
    /** 将一次已校验的规范活动读取投影到 store。 */
    applyThreadActivity(act: GaThreadActivity): void {
      if (act.activeRun) {
        this.activeRunId = act.activeRun.runId
        this.activeStatus = act.activeRun.status
        writeStored(GA_RUN_KEY, act.activeRun.runId)
      } else if (!this.activeRunId) {
        this.activeRunId = null
        this.activeStatus = null
        writeStored(GA_RUN_KEY, null)
      }
      if (act.pendingSteer) {
        const existingOptimistic = this.messages.find((m) => m.id === this.pendingSteer?.optimisticId)
        this.pendingSteer = {
          id: act.pendingSteer.steerId,
          message: act.pendingSteer.message,
          status: act.pendingSteer.status,
          createdAt: act.pendingSteer.createdAt,
          optimisticId: existingOptimistic ? existingOptimistic.id : this.pendingSteer?.optimisticId ?? null,
        }
        this.dedupeOptimistic()
      } else if (!this.steerSending) {
        this.pendingSteer = null
      }
    },
    dedupeOptimistic(): void {
      if (!this.pendingSteer?.message) return
      const canonical = this.messages.find((m) => m.role === 'USER' && m.content === this.pendingSteer?.message && !String(m.id).startsWith('local-'))
      if (canonical && this.pendingSteer.optimisticId) {
        this.messages = this.messages.filter((m) => m.id !== this.pendingSteer?.optimisticId)
        this.pendingSteer = this.pendingSteer ? { ...this.pendingSteer, optimisticId: null } : null
      }
    },
    async ensureThread(): Promise<string> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      if (identityStore.threadId) return identityStore.threadId
      const created = await identityStore.awaitIdentity(identityEpoch, (createGaThread()))
      identityStore.threadId = created.threadId
      writeStored(GA_THREAD_KEY, created.threadId)
      return created.threadId
    
      } catch (identityError) {
        if (identityError instanceof GaIdentityExpiredError) { throw identityError }
        throw identityError
      }
    },
    async loadThreads(): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      if (identityStore.threadsLoading) return
      identityStore.threadsLoading = true
      identityStore.threadsError = null
      try {
        const items = await identityStore.awaitIdentity(identityEpoch, (listGaThreads()))
        const seen = new Set<string>()
        const deduped: GaThreadListItem[] = []
        for (const item of items ?? []) {
          if (!item || !item.threadId || seen.has(item.threadId)) continue
          seen.add(item.threadId)
          deduped.push(item)
        }
        identityStore.threads = deduped
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new GaIdentityExpiredError()

        identityStore.threadsError = err instanceof ApiError ? gaErrorMessage(err.code, err.message) : gaErrorMessage('UNKNOWN_ERROR')
      } finally {
        if (this.identityEpoch === identityEpoch) {
        identityStore.threadsLoading = false
      
        }
      }
    
      } catch (identityError) {
        if (identityError instanceof GaIdentityExpiredError) { return }
        throw identityError
      }
    },
    setHistoryOpen(open: boolean): void {
      this.historyOpen = open
      if (open) void this.loadThreads()
    },
    toggleHistory(): void {
      this.setHistoryOpen(!this.historyOpen)
    },
    async switchThread(threadId: string): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      if (!threadId || identityStore.switchingThread || identityStore.isRunning || identityStore.sending || identityStore.steerSending) return
      if (threadId === identityStore.threadId) {
        identityStore.historyOpen = false
        return
      }
      identityStore.switchingThread = true
      identityStore.error = null
      try {
        let messages: GaMessage[]
        try {
          messages = await identityStore.awaitIdentity(identityEpoch, (listGaMessages(threadId)))
          await identityStore.awaitIdentity(identityEpoch, (getGaThread(threadId)))
        } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new GaIdentityExpiredError()

          if (err instanceof ApiError && err.code === 'THREAD_NOT_FOUND') {
            identityStore.error = { code: 'THREAD_NOT_FOUND', message: '该会话已不存在，已为你保留当前会话' }
            await identityStore.awaitIdentity(identityEpoch, (identityStore.loadThreads()))
            return
          }
          throw err
        }
        identityStore.closeStream()
        identityStore.threadId = threadId
        writeStored(GA_THREAD_KEY, threadId)
        writeStored(GA_RUN_KEY, null)
        identityStore.messages = messages
        identityStore.historicalActivities = {}
        identityStore.historyLoadedRuns = []
        await identityStore.awaitIdentity(identityEpoch, (identityStore.restoreProcessHistory()))
        identityStore.streamingText = ''
      identityStore.streamGeneration = null
        identityStore.activities = []
        identityStore.currentStatus = null
        identityStore.waitingQuestion = null
        identityStore.approvalRequired = false
        identityStore.lastUiAction = null
        identityStore.pendingNavigation = null
        identityStore.stopSuccessorObservation()
        identityStore.activeRunId = null
        identityStore.activeStatus = null
        identityStore.pendingSteer = null
        identityStore.stoppedNotice = false
        identityStore.lastSequence = 0
        identityStore.cancelRequested = false
        identityStore.connection = 'idle'
        identityStore.historyOpen = false
        void identityStore.loadThreads()
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new GaIdentityExpiredError()

        if (err instanceof ApiError) {
          identityStore.error = { code: err.code, message: gaErrorMessage(err.code, err.message) }
        } else {
          identityStore.error = { code: 'UNKNOWN_ERROR', message: gaErrorMessage('UNKNOWN_ERROR') }
        }
      } finally {
        if (this.identityEpoch === identityEpoch) {
        identityStore.switchingThread = false
      
        }
      }
    
      } catch (identityError) {
        if (identityError instanceof GaIdentityExpiredError) { return }
        throw identityError
      }
    },
    async recoverRun(runId: string): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      let run: GaRun
      try {
        run = await identityStore.awaitIdentity(identityEpoch, (getGaRun(runId)))
      } catch {
        if (this.identityEpoch !== identityEpoch) throw new GaIdentityExpiredError()

        writeStored(GA_RUN_KEY, null)
        return
      }
      if (run.status === 'COMPLETED' || run.status === 'FAILED' || run.status === 'CANCELLED') {
        writeStored(GA_RUN_KEY, null)
        return
      }
      identityStore.activeRunId = run.runId
      identityStore.activeStatus = run.status
      identityStore.cancelRequested = false
      writeStored(GA_RUN_KEY, run.runId)
      try {
        const envelopes = await identityStore.awaitIdentity(identityEpoch, (listGaEvents(runId)))
        for (const envelope of envelopes) identityStore.ingestEvent(envelope)
      } catch {
        if (this.identityEpoch !== identityEpoch) throw new GaIdentityExpiredError()
 /* 重放尽力而为,SSE 会补齐 */ }
      identityStore.openStream()
    
      } catch (identityError) {
        if (identityError instanceof GaIdentityExpiredError) { return }
        throw identityError
      }
    },
    ingestEvent(event: GaEventEnvelope): boolean {
      // 上一个运行迟到的事件绝不能污染新运行:
      // 每个运行的 sequence 会重新从 0 计数,所以 runId 是权威过滤条件。
      if (this.activeRunId && event.runId !== this.activeRunId) return false
      if (this.historyLoadedRuns.includes(event.runId) && event.runId !== this.activeRunId) return false
      if (this.threadId && event.threadId !== this.threadId) return false
      if (event.sequence <= this.lastSequence) return false
      const projection = new GaRunProjection()
      projection.lastSequence = this.lastSequence
      projection.streamingText = this.streamingText
      projection.streamGeneration = this.streamGeneration
      projection.currentStatus = this.currentStatus
      projection.activities = this.activities
      projection.waitingQuestion = this.waitingQuestion
      projection.approvalRequired = this.approvalRequired
      projection.uiAction = this.lastUiAction
      const changed = projection.apply(event)
      void changed
      if (this.sendStartedAt !== null) {
        if (this.firstToolResultMs === null && event.type === 'TOOL_COMPLETED') {
          this.firstToolResultMs = Date.now() - this.sendStartedAt
        }
      }
      this.lastSequence = projection.lastSequence
      this.streamingText = projection.streamingText
      this.streamGeneration = projection.streamGeneration
      this.currentStatus = projection.currentStatus
      if (
        (event.type === 'ANSWER_STREAM_STARTED' || event.type === 'ANSWER_DELTA') &&
        this.streamingText.length > 0 &&
        this.currentStatus === GA_SENDING_STATUS
      ) {
        // 真实的流式证据取代乐观的发送期标签。
        // 不用定时器:可见的草稿本身就是"生成中"状态。
        this.currentStatus = GA_GENERATING_STATUS
      }
      this.activities = [...projection.activities]
      this.waitingQuestion = projection.waitingQuestion
      this.approvalRequired = projection.approvalRequired
      if (projection.uiAction && projection.uiAction !== this.lastUiAction) {
        this.lastUiAction = projection.uiAction
        const route = gaUiActionToRoute(projection.uiAction.destination, projection.uiAction.resourceId)
        if (route) this.pendingNavigation = route
      }
      if (projection.terminal) this.finishTerminal(projection.terminal)
      return true
    },
    finishTerminal(terminal: GaTerminal): void {
      // 终态运行绝不留下临时草稿:权威消息通过消息重载到达。
      // 不做这一步,流式草稿会像幽灵一样残留,与最终答案重复。
      this.streamingText = ''
      this.streamGeneration = null
      if (terminal.type === 'RUN_FAILED') {
        const code = terminal.errorCode ?? 'UNKNOWN_ERROR'
        this.error = { code, message: gaErrorMessage(code, terminal.reason ?? undefined) }
        this.stoppedNotice = false
      }
      if (terminal.type === 'RUN_CANCELLED') {
        this.error = null
        this.stoppedNotice = true
      }
      if (terminal.type === 'RUN_COMPLETED') {
        this.stoppedNotice = false
      }
      this.activeStatus = terminal.type === 'RUN_COMPLETED' ? 'COMPLETED' : terminal.type === 'RUN_FAILED' ? 'FAILED' : 'CANCELLED'
      this.currentStatus = null
      this.cancelRequested = false
      this.closeStream()
      this.connection = 'idle'
      writeStored(GA_RUN_KEY, null)
      if (this.activeRunId) {
        this.historicalActivities[this.activeRunId] = [...this.activities]
        this.historyLoadedRuns.push(this.activeRunId)
      }
      this.activeRunId = null
      void this.reconcileMessages()
      void this.loadThreads()
      void this.pollSuccessorAfterTerminal()
    },
    async pollSuccessorAfterTerminal(): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      if (!identityStore.threadId) return
      await identityStore.awaitIdentity(identityEpoch, (identityStore.attachSuccessorIfReady()))
    
      } catch (identityError) {
        if (identityError instanceof GaIdentityExpiredError) { return }
        throw identityError
      }
    },
    /**
     * 终态后的唯一入口:确保由同一代观测者持有该线程,
     * 然后执行一次规范的观测步骤。重入调用复用活跃代(绝不启动第二个循环)。
     */
    async attachSuccessorIfReady(): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      if (!identityStore.threadId) return
      if (identityStore.successorObserverToken === 0) {
        successorObserverSeq += 1
        identityStore.successorObserverToken = successorObserverSeq
      }
      await identityStore.awaitIdentity(identityEpoch, (identityStore.observeSuccessor(identityStore.successorObserverToken, 0)))
    
      } catch (identityError) {
        if (identityError instanceof GaIdentityExpiredError) { return }
        throw identityError
      }
    },
    /**
     * 挂接后继运行:重置单运行投影、重放其事件并打开其事件流。
     * 按运行 id 保证幂等。
     */
    async attachSuccessorRun(runId: string, status: string): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      if (!identityStore.threadId) return
      if (identityStore.successorAttachedRunId === runId) return
      identityStore.stopSuccessorObservation()
      identityStore.successorAttachedRunId = runId
      identityStore.pendingSteer = null
      identityStore.stoppedNotice = false
      identityStore.activeRunId = runId
      identityStore.activeStatus = status
      writeStored(GA_RUN_KEY, runId)
      identityStore.lastSequence = 0
      identityStore.streamingText = ''
      identityStore.streamGeneration = null
      identityStore.activities = []
      identityStore.currentStatus = GA_STEERING_STATUS
      try {
        const envelopes = await identityStore.awaitIdentity(identityEpoch, (listGaEvents(runId)))
        for (const envelope of envelopes) {
          // 重放过程中后继运行可能已经终结:停止向其继续投影。
          if (identityStore.activeRunId !== runId) return
          identityStore.ingestEvent(envelope)
        }
      } catch {
        if (this.identityEpoch !== identityEpoch) throw new GaIdentityExpiredError()
 /* SSE 会补齐 */ }
      if (identityStore.activeRunId !== runId) return
      identityStore.openStream()
      await identityStore.awaitIdentity(identityEpoch, (identityStore.reconcileMessages()))
    
      } catch (identityError) {
        if (identityError instanceof GaIdentityExpiredError) { return }
        throw identityError
      }
    },
    /** 使观测循环失效。进行中的读取/定时器随之变成空操作。 */
    stopSuccessorObservation(): void {
      this.successorObserverToken = 0
      clearSuccessorTimer()
    },
    /**
     * 一次观测步骤。`token` 是确定性的取消归属凭证。
     * 规范读取与投影刻意分离:失去归属(停止/切换线程/删除/重置)的结果
     * 绝没有机会写入 activeRunId、activeStatus 或 pendingSteer。
     */
    async observeSuccessor(token: number, attempt: number): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      if (identityStore.successorObserverToken !== token) return
      if (!identityStore.threadId) {
        identityStore.resolveSuccessorObservation(false)
        return
      }
      const previousRunId = identityStore.activeRunId
      const act = await identityStore.awaitIdentity(identityEpoch, (identityStore.readThreadActivity()))
      // 投影之前先做归属校验:绝不应用过期的读取。
      if (identityStore.successorObserverToken !== token) return
      if (!act) {
        identityStore.scheduleSuccessorObservation(token, attempt + 1)
        return
      }
      const hadPendingSteer = identityStore.pendingSteer !== null
      identityStore.applyThreadActivity(act)
      const run = act.activeRun
      if (run) {
        if (run.runId === previousRunId) identityStore.stopSuccessorObservation()
        else await identityStore.awaitIdentity(identityEpoch, (identityStore.attachSuccessorRun(run.runId, run.status)))
        return
      }
      if (act.pendingSteer) {
        // 交接仍未完成:后继运行只是还没可见。
        // 只要规范的 pending steer 还存在,观测就继续,
        // 降级为慢速轮询而不是放弃。
        identityStore.currentStatus = GA_STEERING_STATUS
        identityStore.scheduleSuccessorObservation(token, attempt + 1)
        return
      }
      // 既没有后继运行也没有待生效 steer:交接已自行落定。
      identityStore.resolveSuccessorObservation(hadPendingSteer)
    
      } catch (identityError) {
        if (identityError instanceof GaIdentityExpiredError) { return }
        throw identityError
      }
    },
    /**
     * 安排下一次观测。前几次读取用快节奏,之后转为持续的低频节奏。
     * 没有墙钟截止时间:只有规范状态落定或生命周期事件才能结束观测。
     */
    scheduleSuccessorObservation(token: number, completedReads: number): void {
      if (this.successorObserverToken !== token) return
      if (!this.threadId) {
        this.resolveSuccessorObservation(false)
        return
      }
      clearSuccessorTimer()
      const delay = completedReads >= GA_SUCCESSOR_OBSERVE_FAST_READS
        ? GA_SUCCESSOR_OBSERVE_SLOW_INTERVAL_MS
        : GA_SUCCESSOR_OBSERVE_INTERVAL_MS
      successorTimer = setTimeout(() => {
        successorTimer = null
        void this.observeSuccessor(token, completedReads)
      }, delay)
    },
    /** 退出观测,回落到线程的规范状态。 */
    resolveSuccessorObservation(reconcileCanonical: boolean): void {
      this.stopSuccessorObservation()
      this.currentStatus = null
      if (reconcileCanonical) void this.reconcileMessages()
    },
    async restoreProcessHistory(): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      const thread = identityStore.threadId
      if (!thread) return
      const runs = [...new Set(identityStore.messages.map(m => m.runId).filter((id): id is string => !!id))]
        .filter(id => id !== identityStore.activeRunId && !identityStore.historyLoadedRuns.includes(id))
      // Bounded concurrency; event replay never performs navigation or appends model drafts.
      for (let start = 0; start < runs.length; start += 4) {
        const batch = await identityStore.awaitIdentity(identityEpoch, (Promise.allSettled(runs.slice(start, start + 4).map(async run => {
          const projection = new GaRunProjection()
          for (const event of await identityStore.awaitIdentity(identityEpoch, (listGaEvents(run)))) {
            if (event.threadId === thread && event.runId === run) projection.apply(event)
          }
          return { run, projection }
        }))))
        if (identityStore.threadId !== thread) return
        for (const result of batch) {
          if (result.status !== 'fulfilled' || !result.value.projection.terminal) continue
          const { run, projection } = result.value
          identityStore.historicalActivities[run] = projection.activities
          identityStore.historyLoadedRuns.push(run)
        }
      }
    
      } catch (identityError) {
        if (identityError instanceof GaIdentityExpiredError) { return }
        throw identityError
      }
    },
    async reconcileMessages(): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      const thread = identityStore.threadId
      if (!thread) return
      try {
        const messages = await identityStore.awaitIdentity(identityEpoch, (listGaMessages(thread)))
        if (identityStore.threadId !== thread) return
        identityStore.messages = messages
        // A history refresh during an active successor must not erase its live draft.
        if (!identityStore.activeRunId) {
          identityStore.streamingText = ''
          identityStore.streamGeneration = null
        }
        identityStore.dedupeOptimistic()
        await identityStore.awaitIdentity(identityEpoch, (identityStore.restoreProcessHistory()))
      } catch {
        if (this.identityEpoch !== identityEpoch) throw new GaIdentityExpiredError()
 /* 保留乐观投影 */ }
    
      } catch (identityError) {
        if (identityError instanceof GaIdentityExpiredError) { return }
        throw identityError
      }
    },
    async sendMessage(text: string, uiContext: GaUiContext): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      const message = text.trim()
      if (!message || identityStore.sending || identityStore.steerSending) return
      if (message.length > 4000) {
        identityStore.error = { code: 'MESSAGE_TOO_LONG', message: gaErrorMessage('MESSAGE_TOO_LONG') }
        identityStore.draft = text
        return
      }
      if (identityStore.isRunning) {
        await identityStore.awaitIdentity(identityEpoch, (identityStore.steerActiveRun(message, uiContext)))
        return
      }
      await identityStore.awaitIdentity(identityEpoch, (identityStore.createIdleRun(message, uiContext)))
    
      } catch (identityError) {
        if (identityError instanceof GaIdentityExpiredError) { return }
        throw identityError
      }
    },
    async steerActiveRun(message: string, uiContext: GaUiContext): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      if (!identityStore.threadId || !identityStore.activeRunId || identityStore.pendingSteer || identityStore.steerSending) return
      identityStore.error = null
      identityStore.steerSending = true
      const optimisticId = 'local-steer-' + Date.now()
      const optimistic: GaMessage = {
        id: optimisticId,
        threadId: identityStore.threadId,
        role: 'USER',
        content: message,
        runId: null,
        createdAt: new Date().toISOString(),
      }
      identityStore.messages = [...identityStore.messages, optimistic]
      identityStore.currentStatus = GA_STEERING_STATUS
      try {
        const result = await identityStore.awaitIdentity(identityEpoch, (steerGaRun(identityStore.activeRunId, message, uiContext)))
        identityStore.pendingSteer = {
          id: result.steerId,
          message,
          status: result.status,
          createdAt: new Date().toISOString(),
          optimisticId,
        }
        identityStore.draft = ''
        if (result.status === 'STARTED' && result.successorRunId) {
          await identityStore.awaitIdentity(identityEpoch, (identityStore.attachSuccessorIfReady()))
        }
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new GaIdentityExpiredError()

        identityStore.messages = identityStore.messages.filter((m) => m.id !== optimisticId)
        identityStore.draft = message
        identityStore.currentStatus = null
        if (err instanceof ApiError) {
          identityStore.error = { code: err.code, message: gaErrorMessage(err.code, err.message) }
        } else {
          identityStore.error = { code: 'UNKNOWN_ERROR', message: gaErrorMessage('UNKNOWN_ERROR') }
        }
      } finally {
        if (this.identityEpoch === identityEpoch) {
        identityStore.steerSending = false
      
        }
      }
    
      } catch (identityError) {
        if (identityError instanceof GaIdentityExpiredError) { return }
        throw identityError
      }
    },
    async createIdleRun(message: string, uiContext: GaUiContext): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      identityStore.error = null
      identityStore.sending = true
      identityStore.draft = ''
      identityStore.stoppedNotice = false
      let threadId = identityStore.threadId
      try {
        if (!threadId) threadId = await identityStore.awaitIdentity(identityEpoch, (identityStore.ensureThread()))
        else {
          try { await identityStore.awaitIdentity(identityEpoch, (getGaThread(threadId))) } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new GaIdentityExpiredError()

            if (err instanceof ApiError && err.code === 'THREAD_NOT_FOUND') {
              writeStored(GA_THREAD_KEY, null)
              writeStored(GA_RUN_KEY, null)
              identityStore.threadId = null
              identityStore.messages = []
              identityStore.activeRunId = null
              identityStore.pendingSteer = null
              void identityStore.loadThreads()
              threadId = await identityStore.awaitIdentity(identityEpoch, (identityStore.ensureThread()))
            } else throw err
          }
        }
        const optimistic: GaMessage = {
          id: 'local-' + Date.now(),
          threadId,
          role: 'USER',
          content: message,
          runId: null,
          createdAt: new Date().toISOString(),
        }
        identityStore.messages = [...identityStore.messages, optimistic]
        identityStore.streamingText = ''
      identityStore.streamGeneration = null
        identityStore.activities = []
        identityStore.currentStatus = GA_SENDING_STATUS
        identityStore.sendStartedAt = Date.now()
        identityStore.firstVisibleUiMs = 0
        identityStore.firstToolResultMs = null
        identityStore.waitingQuestion = null
        identityStore.approvalRequired = false
        identityStore.lastUiAction = null
        let created: { runId: string; status: string }
        try {
          created = await identityStore.awaitIdentity(identityEpoch, (createGaRun(threadId, message, uiContext)))
        } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new GaIdentityExpiredError()

          identityStore.messages = identityStore.messages.filter((m) => m.id !== optimistic.id)
          identityStore.draft = message
          identityStore.currentStatus = null
          if (err instanceof ApiError && err.code === 'GLOBAL_ASSISTANT_RUN_ACTIVE') {
            identityStore.error = { code: err.code, message: gaErrorMessage(err.code) }
            await identityStore.awaitIdentity(identityEpoch, (identityStore.reconcileMessages()))
            return
          }
          if (err instanceof ApiError) {
            identityStore.error = { code: err.code, message: gaErrorMessage(err.code, err.message) }
          } else {
            identityStore.error = { code: 'UNKNOWN_ERROR', message: gaErrorMessage('UNKNOWN_ERROR') }
          }
          return
        }
        identityStore.dedupeCreateOptimistic(optimistic.id, message)
        identityStore.messages = identityStore.messages.map(m => m.id === optimistic.id ? { ...m, runId: created.runId } : m)
        identityStore.activeRunId = created.runId
        identityStore.activeStatus = created.status as GaRun['status']
        identityStore.lastSequence = 0
        identityStore.cancelRequested = false
        identityStore.reconnectAttempts = 0
        writeStored(GA_RUN_KEY, created.runId)
        if (created.status === 'COMPLETED' || created.status === 'FAILED' || created.status === 'CANCELLED') {
          try {
            const envelopes = await identityStore.awaitIdentity(identityEpoch, (listGaEvents(created.runId)))
            for (const envelope of envelopes) identityStore.ingestEvent(envelope)
          } catch {
        if (this.identityEpoch !== identityEpoch) throw new GaIdentityExpiredError()
 /* 继续走对账兜底 */ }
          if (!identityStore.activeRunId) return
          identityStore.finishTerminal({ type: created.status === 'COMPLETED' ? 'RUN_COMPLETED' : created.status === 'FAILED' ? 'RUN_FAILED' : 'RUN_CANCELLED', errorCode: null, reason: null })
          return
        }
        identityStore.openStream()
      } finally {
        if (this.identityEpoch === identityEpoch) {
        identityStore.sending = false
      
        }
      }
    
      } catch (identityError) {
        if (identityError instanceof GaIdentityExpiredError) { return }
        throw identityError
      }
    },
    dedupeCreateOptimistic(optimisticId: string, message: string): void {
      const canonical = this.messages.find((m) => m.role === 'USER' && m.content === message && !String(m.id).startsWith('local-'))
      if (canonical) {
        this.messages = this.messages.filter((m) => m.id !== optimisticId)
      } else {
        this.messages = this.messages.map((m) => (m.id === optimisticId ? { ...m, id: 'pending-' + optimisticId } : m))
      }
    },
    openStream(): void {
      if (!this.activeRunId) return
      const identityEpoch = this.identityEpoch
      this.closeStream()
      const runId = this.activeRunId
      this.connection = 'connecting'
      const handle = openGaEventStream(runId, this.lastSequence, {
        onEvent: (event) => {
          if (this.identityEpoch !== identityEpoch) return
          if (event.runId !== this.activeRunId) return
          this.connection = 'connected'
          this.reconnectAttempts = 0
          this.ingestEvent(event)
        },
        onError: () => { if (this.identityEpoch === identityEpoch) void this.handleStreamError(runId) },
      }, streamAbortSignal())
      setStreamHandle(handle)
    },
    async handleStreamError(runId: string): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      if (identityStore.activeRunId !== runId) return
      if (!identityStore.activeRunId) return
      identityStore.connection = 'reconnecting'
      try {
        const run = await identityStore.awaitIdentity(identityEpoch, (getGaRun(runId)))
        if (run.status === 'COMPLETED' || run.status === 'FAILED' || run.status === 'CANCELLED') {
          try {
            const envelopes = await identityStore.awaitIdentity(identityEpoch, (listGaEvents(runId)))
            for (const envelope of envelopes) identityStore.ingestEvent(envelope)
          } catch {
        if (this.identityEpoch !== identityEpoch) throw new GaIdentityExpiredError()
 /* 忽略 */ }
          if (identityStore.activeRunId === runId && !identityStore.lastSequenceTerminal()) {
            identityStore.finishTerminal({ type: run.status === 'COMPLETED' ? 'RUN_COMPLETED' : run.status === 'FAILED' ? 'RUN_FAILED' : 'RUN_CANCELLED', errorCode: run.errorCode, reason: null })
          }
          return
        }
      } catch {
        if (this.identityEpoch !== identityEpoch) throw new GaIdentityExpiredError()
 /* 继续走重试 */ }
      try {
        const envelopes = await identityStore.awaitIdentity(identityEpoch, (listGaEvents(runId)))
        for (const envelope of envelopes) {
          if (identityStore.activeRunId !== runId) return
          identityStore.ingestEvent(envelope)
        }
      } catch {
        if (this.identityEpoch !== identityEpoch) throw new GaIdentityExpiredError()
 /* 忽略 */ }
      if (identityStore.activeRunId !== runId) return
      if (identityStore.reconnectAttempts >= 5) {
        identityStore.connection = 'disconnected'
        return
      }
      identityStore.reconnectAttempts += 1
      const delay = Math.min(5000, 800 * identityStore.reconnectAttempts)
      await identityStore.awaitIdentity(identityEpoch, (sleep(delay)))
      if (identityStore.activeRunId !== runId) return
      identityStore.openStream()
    
      } catch (identityError) {
        if (identityError instanceof GaIdentityExpiredError) { return }
        throw identityError
      }
    },
    lastSequenceTerminal(): boolean {
      return this.activeRunId === null
    },
    retryConnection(): void {
      if (!this.activeRunId) return
      this.reconnectAttempts = 0
      this.connection = 'connecting'
      this.openStream()
    },
    async cancelActiveRun(): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      if (!identityStore.threadId || identityStore.cancelRequested) return
      if (!identityStore.activeRunId && !identityStore.pendingSteer) return
      identityStore.cancelRequested = true
      // 显式停止会一并取消任何待生效的 steer 续接。
      identityStore.stopSuccessorObservation()
      identityStore.currentStatus = '正在停止…'
      try {
        const act = await identityStore.awaitIdentity(identityEpoch, (stopGaThread(identityStore.threadId)))
        if (!act.activeRun && !act.pendingSteer) {
          identityStore.pendingSteer = null
        } else if (!act.pendingSteer) {
          identityStore.pendingSteer = null
        }
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new GaIdentityExpiredError()

        identityStore.cancelRequested = false
        identityStore.currentStatus = null
        if (err instanceof ApiError) {
          identityStore.error = { code: err.code, message: gaErrorMessage(err.code, err.message) }
        }
      }
    
      } catch (identityError) {
        if (identityError instanceof GaIdentityExpiredError) { return }
        throw identityError
      }
    },
    async deleteThread(threadId: string): Promise<boolean> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      if (!threadId || identityStore.deletingThreadId) return false
      identityStore.deletingThreadId = threadId
      identityStore.error = null
      if (threadId === identityStore.threadId) identityStore.stopSuccessorObservation()
      try {
        await identityStore.awaitIdentity(identityEpoch, (deleteGaThread(threadId)))
        const wasCurrent = threadId === identityStore.threadId
        await identityStore.awaitIdentity(identityEpoch, (identityStore.loadThreads()))
        if (wasCurrent) {
          const next = identityStore.threads.find((t) => t.threadId !== threadId) ?? null
          identityStore.closeStream()
          if (next) {
            await identityStore.awaitIdentity(identityEpoch, (identityStore.switchThreadIdle(next.threadId)))
          } else {
            identityStore.threadId = null
            writeStored(GA_THREAD_KEY, null)
            writeStored(GA_RUN_KEY, null)
            identityStore.messages = []
            identityStore.streamingText = ''
            identityStore.streamGeneration = null
            identityStore.activities = []
            identityStore.currentStatus = null
            identityStore.waitingQuestion = null
            identityStore.activeRunId = null
            identityStore.activeStatus = null
            identityStore.pendingSteer = null
            identityStore.stoppedNotice = false
          }
        }
        identityStore.confirmDeleteThreadId = null
        return true
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new GaIdentityExpiredError()

        if (err instanceof ApiError) {
          identityStore.error = { code: err.code, message: gaErrorMessage(err.code, err.message) }
        } else {
          identityStore.error = { code: 'UNKNOWN_ERROR', message: gaErrorMessage('UNKNOWN_ERROR') }
        }
        return false
      } finally {
        if (this.identityEpoch === identityEpoch) {
        identityStore.deletingThreadId = null
      
        }
      }
    
      } catch (identityError) {
        if (identityError instanceof GaIdentityExpiredError) { return false }
        throw identityError
      }
    },
    async switchThreadIdle(threadId: string): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      identityStore.switchingThread = true
      try {
        const messages = await identityStore.awaitIdentity(identityEpoch, (listGaMessages(threadId)))
        await identityStore.awaitIdentity(identityEpoch, (getGaThread(threadId)))
        identityStore.closeStream()
        identityStore.threadId = threadId
        writeStored(GA_THREAD_KEY, threadId)
        writeStored(GA_RUN_KEY, null)
        identityStore.messages = messages
        identityStore.historicalActivities = {}
        identityStore.historyLoadedRuns = []
        await identityStore.awaitIdentity(identityEpoch, (identityStore.restoreProcessHistory()))
        identityStore.streamingText = ''
      identityStore.streamGeneration = null
        identityStore.activities = []
        identityStore.currentStatus = null
        identityStore.waitingQuestion = null
        identityStore.approvalRequired = false
        identityStore.stopSuccessorObservation()
        identityStore.activeRunId = null
        identityStore.activeStatus = null
        identityStore.pendingSteer = null
        identityStore.stoppedNotice = false
        identityStore.lastSequence = 0
        identityStore.cancelRequested = false
        identityStore.connection = 'idle'
        identityStore.historyOpen = false
        await identityStore.awaitIdentity(identityEpoch, (identityStore.refreshActivity()))
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new GaIdentityExpiredError()

        if (err instanceof ApiError && err.code === 'THREAD_NOT_FOUND') {
          identityStore.error = { code: 'THREAD_NOT_FOUND', message: '该会话已不存在，已为你保留当前会话' }
          await identityStore.awaitIdentity(identityEpoch, (identityStore.loadThreads()))
        } else if (err instanceof ApiError) {
          identityStore.error = { code: err.code, message: gaErrorMessage(err.code, err.message) }
        }
      } finally {
        if (this.identityEpoch === identityEpoch) {
        identityStore.switchingThread = false
      
        }
      }
    
      } catch (identityError) {
        if (identityError instanceof GaIdentityExpiredError) { return }
        throw identityError
      }
    },
    async startNewConversation(): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      if (identityStore.sending || identityStore.isRunning || identityStore.steerSending) return
      identityStore.closeStream()
      identityStore.error = null
      identityStore.historyOpen = false
      try {
        const created = await identityStore.awaitIdentity(identityEpoch, (createGaThread()))
        identityStore.threadId = created.threadId
        writeStored(GA_THREAD_KEY, created.threadId)
        writeStored(GA_RUN_KEY, null)
        identityStore.messages = []
        identityStore.streamingText = ''
      identityStore.streamGeneration = null
        identityStore.activities = []
        identityStore.currentStatus = null
        identityStore.waitingQuestion = null
        identityStore.approvalRequired = false
        identityStore.lastUiAction = null
        identityStore.pendingNavigation = null
        identityStore.stopSuccessorObservation()
        identityStore.activeRunId = null
        identityStore.activeStatus = null
        identityStore.lastSequence = 0
        identityStore.cancelRequested = false
        identityStore.connection = 'idle'
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new GaIdentityExpiredError()

        if (err instanceof ApiError) identityStore.error = { code: err.code, message: gaErrorMessage(err.code, err.message) }
      }
    
      } catch (identityError) {
        if (identityError instanceof GaIdentityExpiredError) { return }
        throw identityError
      }
    },
    closeStream(): void {
      closeStreamHandle()
    },
    $resetForTest(): void {
      this.stopSuccessorObservation()
      this.successorAttachedRunId = null
      this.closeStream()
      this.threadId = null
      this.historicalActivities = {}
      this.historyLoadedRuns = []
      this.messages = []
      this.activeRunId = null
      this.activeStatus = null
      this.lastSequence = 0
      this.streamingText = ''
      this.streamGeneration = null
      this.activities = []
      this.currentStatus = null
      this.waitingQuestion = null
      this.approvalRequired = false
      this.pendingNavigation = null
      this.lastUiAction = null
      this.cancelRequested = false
      this.connection = 'idle'
      this.error = null
      this.sending = false
      this.draft = ''
      this.reconnectAttempts = 0
      this.threads = []
      this.threadsLoading = false
      this.threadsError = null
      this.historyOpen = false
      this.switchingThread = false
      this.loadingThread = false
      this.pendingSteer = null
      this.steerSending = false
      this.stoppedNotice = false
      this.deletingThreadId = null
      this.confirmDeleteThreadId = null
    },
  },
})

let activeHandle: { close: () => void } | null = null
let activeController: AbortController | null = null
/** BUG-01 后继运行观测:单调递增令牌 + 独占定时器。 */
let successorObserverSeq = 0
let successorTimer: ReturnType<typeof setTimeout> | null = null

function clearSuccessorTimer(): void {
  if (successorTimer !== null) {
    clearTimeout(successorTimer)
    successorTimer = null
  }
}

function streamAbortSignal(): AbortSignal {
  activeController = new AbortController()
  return activeController.signal
}

function setStreamHandle(handle: { close: () => void }): void {
  activeHandle = handle
}

function closeStreamHandle(): void {
  try { activeHandle?.close() } catch { /* ignore */ }
  activeHandle = null
  try { activeController?.abort() } catch { /* ignore */ }
  activeController = null
}

const reconnectWaits = new Map<ReturnType<typeof setTimeout>, () => void>()
function cancelReconnectWaits(): void {
  for (const [timer, resolve] of reconnectWaits) { clearTimeout(timer); resolve() }
  reconnectWaits.clear()
}
function sleep(ms: number): Promise<void> {
  return new Promise(resolve => {
    const timer = setTimeout(() => { reconnectWaits.delete(timer); resolve() }, ms)
    reconnectWaits.set(timer, resolve)
  })
}
