import { defineStore } from 'pinia'
import { ApiError } from '@/api/client'
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
} from '@/api/globalAssistant'
import { openGaEventStream } from '@/api/globalAssistantEvents'
import { gaErrorMessage, gaToolDisplayName, gaArgsSummary, gaStatusMessage, GA_SENDING_STATUS, GA_GENERATING_STATUS } from '@/presentation/globalAssistantPresentation'

export const GA_THREAD_KEY = 'spec-agent:global-assistant:thread:v1'
export const GA_RUN_KEY = 'spec-agent:global-assistant:run:v1'
export const GA_PANEL_KEY = 'spec-agent:global-assistant:panel:v1'

export type GaToolState = 'running' | 'success' | 'failure'

export interface GaResourceRef {
  kind: string
  id: string
  label: string
  metadata?: Record<string, unknown> | null
}

const GA_UUID_RE = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/

/** Shown while a steer handoff is pending and the successor is not visible yet. */
const GA_STEERING_STATUS = '正在调整方向…'

/**
 * BUG-01: the backend creates the steer successor in an AFTER_COMMIT phase, so
 * canonical thread activity legitimately reports `{ activeRun: null, pendingSteer: S }`
 * for a window of unknown length. The store must keep observing activity until
 * the handoff resolves instead of giving up after a single read.
 *
 * Observation lifetime is driven by canonical state, never by a deadline:
 * fast reads first, then a sustained slow cadence while the pending steer is
 * still unresolved. Only canonical resolution or a lifecycle event ends it.
 */
export const GA_SUCCESSOR_OBSERVE_INTERVAL_MS = 400
export const GA_SUCCESSOR_OBSERVE_FAST_READS = 10
export const GA_SUCCESSOR_OBSERVE_SLOW_INTERVAL_MS = 1500

/** Code-point-safe bounded truncation: Array.from splits by code point, never by UTF-16 unit. */
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
    if (m.kind !== 'PROJECT') continue
    if (typeof m.id !== 'string' || !GA_UUID_RE.test(m.id)) continue
    if (typeof m.label !== 'string' || !m.label) continue
    const label = truncateGaLabel(m.label)
    let metadata: Record<string, unknown> | null = null
    if (m.metadata && typeof m.metadata === 'object') {
      const mm = m.metadata as Record<string, unknown>
      if (typeof mm.updatedAt === 'string') metadata = { updatedAt: mm.updatedAt.slice(0, 64) }
    }
    out.push({ kind: 'PROJECT', id: m.id, label, metadata })
    if (out.length >= 10) break
  }
  return out
}

export interface GaToolActivity {
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
  /** Structured result kind from the TOOL_COMPLETED event (generic rendering). */
  resultKind: string | null
  /** Trustworthy result count: sanitized refs length wins, else event resultCount. */
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

/** Deterministic per-run projection. Pure and unit-testable. */
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

  /** Returns true when the event produced a visible change. */
  apply(event: GaEventEnvelope): boolean {
    if (!event || typeof event.sequence !== 'number') return false
    if (event.sequence <= this.lastSequence) return false
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
        // Legacy at-once message event (old persisted runs, failure texts,
        // mocked streams). New runs use ANSWER_STREAM_* with generations.
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
          // New generation without an explicit STARTED (e.g. resubscribe
          // snapshot): reconcile by replacing the stale draft.
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
        const resultCount = resourceRefs.length > 0 ? resourceRefs.length : eventCount
        const target = findRunningActivity(this.activities, capabilityId)
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
        const summary = errorCode ? gaErrorMessage(errorCode, reason ?? undefined) : (reason ?? '工具执行失败，请稍后再试。')
        const target = findRunningActivity(this.activities, capabilityId)
        if (target) {
          target.state = 'failure'
          target.summary = summary
          target.endedAt = typeof event.createdAt === 'string' ? event.createdAt : target.endedAt
          target.durationMs = diffMs(target.startedAt, target.endedAt)
          return true
        }
        this.toolSeq += 1
        this.activities.push({
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
        // V1 has no approval-response endpoint: restrained non-interactive state only.
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
        this.terminal = { type: 'RUN_COMPLETED', errorCode: null, reason: null }
        this.currentStatus = null
        return true
      case 'RUN_CANCELLED':
        this.terminal = { type: 'RUN_CANCELLED', errorCode: null, reason: null }
        this.currentStatus = null
        return true
      case 'RUN_FAILED': {
        const errorCode = typeof payload.errorCode === 'string' ? payload.errorCode : null
        const reason = typeof payload.reason === 'string' ? payload.reason : null
        this.terminal = { type: 'RUN_FAILED', errorCode, reason }
        this.currentStatus = null
        return true
      }
      default:
        return false
    }
  }
}

function findRunningActivity(list: GaToolActivity[], capabilityId: string): GaToolActivity | null {
  for (let i = list.length - 1; i >= 0; i -= 1) {
    const item = list[i]
    if (item && item.capabilityId === capabilityId && item.state === 'running') return item
  }
  return null
}

function diffMs(startedAt: string, endedAt: string | null): number | null {
  if (!endedAt) return null
  const start = Date.parse(startedAt)
  const end = Date.parse(endedAt)
  if (Number.isNaN(start) || Number.isNaN(end)) return null
  const diff = end - start
  return diff >= 0 ? Math.round(diff) : null
}

function readStored(key: string): string | null {
  try {
    const value = localStorage.getItem(key)
    return value && value.length > 0 ? value : null
  } catch {
    return null
  }
}

function writeStored(key: string, value: string | null): void {
  try {
    if (value === null) localStorage.removeItem(key)
    else localStorage.setItem(key, value)
  } catch {
    /* storage unavailable: backend remains canonical */
  }
}

export const useGlobalAssistantStore = defineStore('globalAssistant', {
  state: () => ({
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
    /** Non-zero while a successor-observation loop owns this thread (singleton token). */
    successorObserverToken: 0 as number,
    /** Last successor run already attached; makes attach idempotent. */
    successorAttachedRunId: null as string | null,
  }),
  getters: {
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
      if (this.initialized) return
      this.initialized = true
      this.initPanel()
      const storedThread = readStored(GA_THREAD_KEY)
      const storedRun = readStored(GA_RUN_KEY)
      if (!storedThread) {
        void this.loadThreads()
        return
      }
      this.loadingThread = true
      try {
        await getGaThread(storedThread)
        this.threadId = storedThread
        this.messages = await listGaMessages(storedThread)
        await this.refreshActivity()
        if (this.activeRunId) {
          try {
            const envelopes = await listGaEvents(this.activeRunId)
            for (const envelope of envelopes) this.ingestEvent(envelope)
          } catch { /* replay best-effort; SSE catches up */ }
          if (this.activeRunId) this.openStream()
        } else if (storedRun && !this.pendingSteer) {
          await this.recoverRun(storedRun)
        }
      } catch (err) {
        if (err instanceof ApiError && err.code === 'THREAD_NOT_FOUND') {
          writeStored(GA_THREAD_KEY, null)
          writeStored(GA_RUN_KEY, null)
          this.threadId = null
          this.messages = []
          this.activeRunId = null
          this.pendingSteer = null
        } else if (err instanceof ApiError) {
          this.threadId = storedThread
          try { this.messages = await listGaMessages(storedThread) } catch { /* keep empty */ }
          this.error = { code: err.code, message: gaErrorMessage(err.code, err.message) }
        }
      } finally {
        this.loadingThread = false
      }
      void this.loadThreads()
    },
    async refreshActivity(): Promise<GaThreadActivity | null> {
      const act = await this.readThreadActivity()
      if (!act) return null
      this.applyThreadActivity(act)
      return act
    },
    /**
     * Canonical read with the thread guard. Deliberately projection-free so
     * callers can validate ownership BEFORE anything is written to state.
     */
    async readThreadActivity(): Promise<GaThreadActivity | null> {
      if (!this.threadId) return null
      const requestedThreadId = this.threadId
      try {
        const act = await getGaThreadActivity(this.threadId)
        // A read that lands after a thread switch belongs to the old thread.
        if (this.threadId !== requestedThreadId) return null
        return act
      } catch {
        return null
      }
    },
    /** Projects a validated canonical activity read onto the store. */
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
      if (this.threadId) return this.threadId
      const created = await createGaThread()
      this.threadId = created.threadId
      writeStored(GA_THREAD_KEY, created.threadId)
      return created.threadId
    },
    async loadThreads(): Promise<void> {
      if (this.threadsLoading) return
      this.threadsLoading = true
      this.threadsError = null
      try {
        const items = await listGaThreads()
        const seen = new Set<string>()
        const deduped: GaThreadListItem[] = []
        for (const item of items ?? []) {
          if (!item || !item.threadId || seen.has(item.threadId)) continue
          seen.add(item.threadId)
          deduped.push(item)
        }
        this.threads = deduped
      } catch (err) {
        this.threadsError = err instanceof ApiError ? gaErrorMessage(err.code, err.message) : gaErrorMessage('UNKNOWN_ERROR')
      } finally {
        this.threadsLoading = false
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
      if (!threadId || this.switchingThread || this.isRunning || this.sending || this.steerSending) return
      if (threadId === this.threadId) {
        this.historyOpen = false
        return
      }
      this.switchingThread = true
      this.error = null
      try {
        let messages: GaMessage[]
        try {
          messages = await listGaMessages(threadId)
          await getGaThread(threadId)
        } catch (err) {
          if (err instanceof ApiError && err.code === 'THREAD_NOT_FOUND') {
            this.error = { code: 'THREAD_NOT_FOUND', message: '该会话已不存在，已为你保留当前会话。' }
            await this.loadThreads()
            return
          }
          throw err
        }
        this.closeStream()
        this.threadId = threadId
        writeStored(GA_THREAD_KEY, threadId)
        writeStored(GA_RUN_KEY, null)
        this.messages = messages
        this.streamingText = ''
      this.streamGeneration = null
        this.activities = []
        this.currentStatus = null
        this.waitingQuestion = null
        this.approvalRequired = false
        this.lastUiAction = null
        this.pendingNavigation = null
        this.stopSuccessorObservation()
        this.activeRunId = null
        this.activeStatus = null
        this.pendingSteer = null
        this.stoppedNotice = false
        this.lastSequence = 0
        this.cancelRequested = false
        this.connection = 'idle'
        this.historyOpen = false
        void this.loadThreads()
      } catch (err) {
        if (err instanceof ApiError) {
          this.error = { code: err.code, message: gaErrorMessage(err.code, err.message) }
        } else {
          this.error = { code: 'UNKNOWN_ERROR', message: gaErrorMessage('UNKNOWN_ERROR') }
        }
      } finally {
        this.switchingThread = false
      }
    },
    async recoverRun(runId: string): Promise<void> {
      let run: GaRun
      try {
        run = await getGaRun(runId)
      } catch {
        writeStored(GA_RUN_KEY, null)
        return
      }
      if (run.status === 'COMPLETED' || run.status === 'FAILED' || run.status === 'CANCELLED') {
        writeStored(GA_RUN_KEY, null)
        return
      }
      this.activeRunId = run.runId
      this.activeStatus = run.status
      this.cancelRequested = false
      writeStored(GA_RUN_KEY, run.runId)
      try {
        const envelopes = await listGaEvents(runId)
        for (const envelope of envelopes) this.ingestEvent(envelope)
      } catch { /* replay is best-effort; SSE will catch up */ }
      this.openStream()
    },
    ingestEvent(event: GaEventEnvelope): boolean {
      // Late events from a previous run must never contaminate the new run:
      // per-run sequences restart, so runId is the authoritative filter.
      if (this.activeRunId && event.runId !== this.activeRunId) return false
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
        // Real stream evidence supersedes the optimistic send-time label.
        // No timers: the visible draft itself is the generating state.
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
      // A terminal run never leaves a transient draft behind: the authoritative
      // message arrives via messages reload. Without this the streamed draft
      // would linger as a ghost duplicate of the final answer.
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
      this.activeRunId = null
      void this.reconcileMessages()
      void this.loadThreads()
      void this.pollSuccessorAfterTerminal()
    },
    async pollSuccessorAfterTerminal(): Promise<void> {
      if (!this.threadId) return
      await this.attachSuccessorIfReady()
    },
    /**
     * Single entry point after a terminal: ensures one observer generation
     * owns the thread, then performs one canonical observation step.
     * Re-entrant calls reuse the active generation (never a second loop).
     */
    async attachSuccessorIfReady(): Promise<void> {
      if (!this.threadId) return
      if (this.successorObserverToken === 0) {
        successorObserverSeq += 1
        this.successorObserverToken = successorObserverSeq
      }
      await this.observeSuccessor(this.successorObserverToken, 0)
    },
    /**
     * Attaches the successor run: resets the per-run projection, replays its
     * events and opens its stream. Idempotent per run id.
     */
    async attachSuccessorRun(runId: string, status: string): Promise<void> {
      if (!this.threadId) return
      if (this.successorAttachedRunId === runId) return
      this.stopSuccessorObservation()
      this.successorAttachedRunId = runId
      this.pendingSteer = null
      this.stoppedNotice = false
      this.activeRunId = runId
      this.activeStatus = status
      writeStored(GA_RUN_KEY, runId)
      this.lastSequence = 0
      this.streamingText = ''
      this.streamGeneration = null
      this.activities = []
      this.currentStatus = GA_STEERING_STATUS
      try {
        const envelopes = await listGaEvents(runId)
        for (const envelope of envelopes) {
          // Replay may terminalize the successor: stop projecting into it.
          if (this.activeRunId !== runId) return
          this.ingestEvent(envelope)
        }
      } catch { /* SSE catches up */ }
      if (this.activeRunId !== runId) return
      this.openStream()
      await this.reconcileMessages()
    },
    /** Invalidates the observation loop. Pending reads/timers become no-ops. */
    stopSuccessorObservation(): void {
      this.successorObserverToken = 0
      clearSuccessorTimer()
    },
    /**
     * One observation step. `token` is the deterministic cancellation ownership.
     * The canonical read is deliberately separated from its projection: a result
     * that lost ownership (stop / thread switch / delete / reset) never gets the
     * chance to write activeRunId, activeStatus or pendingSteer.
     */
    async observeSuccessor(token: number, attempt: number): Promise<void> {
      if (this.successorObserverToken !== token) return
      if (!this.threadId) {
        this.resolveSuccessorObservation(false)
        return
      }
      const previousRunId = this.activeRunId
      const act = await this.readThreadActivity()
      // Ownership check BEFORE projection: never apply a stale read.
      if (this.successorObserverToken !== token) return
      if (!act) {
        this.scheduleSuccessorObservation(token, attempt + 1)
        return
      }
      const hadPendingSteer = this.pendingSteer !== null
      this.applyThreadActivity(act)
      const run = act.activeRun
      if (run) {
        if (run.runId === previousRunId) this.stopSuccessorObservation()
        else await this.attachSuccessorRun(run.runId, run.status)
        return
      }
      if (act.pendingSteer) {
        // Handoff still pending: the successor simply is not visible yet.
        // Observation stays alive for as long as the canonical pending steer
        // exists, downshifting to slow polling instead of giving up.
        this.currentStatus = GA_STEERING_STATUS
        this.scheduleSuccessorObservation(token, attempt + 1)
        return
      }
      // No successor and no pending steer: the handoff resolved on its own.
      this.resolveSuccessorObservation(hadPendingSteer)
    },
    /**
     * Schedules the next observation. Fast cadence for the first reads, then a
     * sustained slow cadence. There is no wall-clock deadline: only canonical
     * resolution or a lifecycle event ends the observation.
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
    /** Exits observation and falls back to canonical thread state. */
    resolveSuccessorObservation(reconcileCanonical: boolean): void {
      this.stopSuccessorObservation()
      this.currentStatus = null
      if (reconcileCanonical) void this.reconcileMessages()
    },
    async reconcileMessages(): Promise<void> {
      if (!this.threadId) return
      try {
        this.messages = await listGaMessages(this.threadId)
        this.streamingText = ''
        this.streamGeneration = null
        this.dedupeOptimistic()
      } catch { /* keep optimistic projection */ }
    },
    async sendMessage(text: string, uiContext: GaUiContext): Promise<void> {
      const message = text.trim()
      if (!message || this.sending || this.steerSending) return
      if (message.length > 4000) {
        this.error = { code: 'MESSAGE_TOO_LONG', message: gaErrorMessage('MESSAGE_TOO_LONG') }
        this.draft = text
        return
      }
      if (this.isRunning) {
        await this.steerActiveRun(message, uiContext)
        return
      }
      await this.createIdleRun(message, uiContext)
    },
    async steerActiveRun(message: string, uiContext: GaUiContext): Promise<void> {
      if (!this.threadId || !this.activeRunId || this.pendingSteer || this.steerSending) return
      this.error = null
      this.steerSending = true
      const optimisticId = 'local-steer-' + Date.now()
      const optimistic: GaMessage = {
        id: optimisticId,
        threadId: this.threadId,
        role: 'USER',
        content: message,
        runId: null,
        createdAt: new Date().toISOString(),
      }
      this.messages = [...this.messages, optimistic]
      this.currentStatus = GA_STEERING_STATUS
      try {
        const result = await steerGaRun(this.activeRunId, message, uiContext)
        this.pendingSteer = {
          id: result.steerId,
          message,
          status: result.status,
          createdAt: new Date().toISOString(),
          optimisticId,
        }
        this.draft = ''
        if (result.status === 'STARTED' && result.successorRunId) {
          await this.attachSuccessorIfReady()
        }
      } catch (err) {
        this.messages = this.messages.filter((m) => m.id !== optimisticId)
        this.draft = message
        this.currentStatus = null
        if (err instanceof ApiError) {
          this.error = { code: err.code, message: gaErrorMessage(err.code, err.message) }
        } else {
          this.error = { code: 'UNKNOWN_ERROR', message: gaErrorMessage('UNKNOWN_ERROR') }
        }
      } finally {
        this.steerSending = false
      }
    },
    async createIdleRun(message: string, uiContext: GaUiContext): Promise<void> {
      this.error = null
      this.sending = true
      this.draft = ''
      this.stoppedNotice = false
      let threadId = this.threadId
      try {
        if (!threadId) threadId = await this.ensureThread()
        else {
          try { await getGaThread(threadId) } catch (err) {
            if (err instanceof ApiError && err.code === 'THREAD_NOT_FOUND') {
              writeStored(GA_THREAD_KEY, null)
              writeStored(GA_RUN_KEY, null)
              this.threadId = null
              this.messages = []
              this.activeRunId = null
              this.pendingSteer = null
              void this.loadThreads()
              threadId = await this.ensureThread()
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
        this.messages = [...this.messages, optimistic]
        this.streamingText = ''
      this.streamGeneration = null
        this.activities = []
        this.currentStatus = GA_SENDING_STATUS
        this.sendStartedAt = Date.now()
        this.firstVisibleUiMs = 0
        this.firstToolResultMs = null
        this.waitingQuestion = null
        this.approvalRequired = false
        this.lastUiAction = null
        let created: { runId: string; status: string }
        try {
          created = await createGaRun(threadId, message, uiContext)
        } catch (err) {
          this.messages = this.messages.filter((m) => m.id !== optimistic.id)
          this.draft = message
          this.currentStatus = null
          if (err instanceof ApiError && err.code === 'GLOBAL_ASSISTANT_RUN_ACTIVE') {
            this.error = { code: err.code, message: gaErrorMessage(err.code) }
            await this.reconcileMessages()
            return
          }
          if (err instanceof ApiError) {
            this.error = { code: err.code, message: gaErrorMessage(err.code, err.message) }
          } else {
            this.error = { code: 'UNKNOWN_ERROR', message: gaErrorMessage('UNKNOWN_ERROR') }
          }
          return
        }
        this.dedupeCreateOptimistic(optimistic.id, message)
        this.activeRunId = created.runId
        this.activeStatus = created.status as GaRun['status']
        this.lastSequence = 0
        this.cancelRequested = false
        this.reconnectAttempts = 0
        writeStored(GA_RUN_KEY, created.runId)
        if (created.status === 'COMPLETED' || created.status === 'FAILED' || created.status === 'CANCELLED') {
          try {
            const envelopes = await listGaEvents(created.runId)
            for (const envelope of envelopes) this.ingestEvent(envelope)
          } catch { /* fall through to reconcile */ }
          if (!this.activeRunId) return
          this.finishTerminal({ type: created.status === 'COMPLETED' ? 'RUN_COMPLETED' : created.status === 'FAILED' ? 'RUN_FAILED' : 'RUN_CANCELLED', errorCode: null, reason: null })
          return
        }
        this.openStream()
      } finally {
        this.sending = false
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
      this.closeStream()
      const runId = this.activeRunId
      this.connection = 'connecting'
      const handle = openGaEventStream(runId, this.lastSequence, {
        onEvent: (event) => {
          if (event.runId !== this.activeRunId) return
          this.connection = 'connected'
          this.reconnectAttempts = 0
          this.ingestEvent(event)
        },
        onError: () => { void this.handleStreamError(runId) },
      }, streamAbortSignal())
      setStreamHandle(handle)
    },
    async handleStreamError(runId: string): Promise<void> {
      if (this.activeRunId !== runId) return
      if (!this.activeRunId) return
      this.connection = 'reconnecting'
      try {
        const run = await getGaRun(runId)
        if (run.status === 'COMPLETED' || run.status === 'FAILED' || run.status === 'CANCELLED') {
          try {
            const envelopes = await listGaEvents(runId)
            for (const envelope of envelopes) this.ingestEvent(envelope)
          } catch { /* ignore */ }
          if (this.activeRunId === runId && !this.lastSequenceTerminal()) {
            this.finishTerminal({ type: run.status === 'COMPLETED' ? 'RUN_COMPLETED' : run.status === 'FAILED' ? 'RUN_FAILED' : 'RUN_CANCELLED', errorCode: run.errorCode, reason: null })
          }
          return
        }
      } catch { /* fall through to retry */ }
      try {
        const envelopes = await listGaEvents(runId)
        for (const envelope of envelopes) {
          if (this.activeRunId !== runId) return
          this.ingestEvent(envelope)
        }
      } catch { /* ignore */ }
      if (this.activeRunId !== runId) return
      if (this.reconnectAttempts >= 5) {
        this.connection = 'disconnected'
        return
      }
      this.reconnectAttempts += 1
      const delay = Math.min(5000, 800 * this.reconnectAttempts)
      await sleep(delay)
      if (this.activeRunId !== runId) return
      this.openStream()
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
      if (!this.threadId || this.cancelRequested) return
      if (!this.activeRunId && !this.pendingSteer) return
      this.cancelRequested = true
      // An explicit stop cancels any pending steer continuation.
      this.stopSuccessorObservation()
      this.currentStatus = '正在停止…'
      try {
        const act = await stopGaThread(this.threadId)
        if (!act.activeRun && !act.pendingSteer) {
          this.pendingSteer = null
        } else if (!act.pendingSteer) {
          this.pendingSteer = null
        }
      } catch (err) {
        this.cancelRequested = false
        this.currentStatus = null
        if (err instanceof ApiError) {
          this.error = { code: err.code, message: gaErrorMessage(err.code, err.message) }
        }
      }
    },
    async deleteThread(threadId: string): Promise<boolean> {
      if (!threadId || this.deletingThreadId) return false
      this.deletingThreadId = threadId
      this.error = null
      if (threadId === this.threadId) this.stopSuccessorObservation()
      try {
        await deleteGaThread(threadId)
        const wasCurrent = threadId === this.threadId
        await this.loadThreads()
        if (wasCurrent) {
          const next = this.threads.find((t) => t.threadId !== threadId) ?? null
          this.closeStream()
          if (next) {
            await this.switchThreadIdle(next.threadId)
          } else {
            this.threadId = null
            writeStored(GA_THREAD_KEY, null)
            writeStored(GA_RUN_KEY, null)
            this.messages = []
            this.streamingText = ''
            this.streamGeneration = null
            this.activities = []
            this.currentStatus = null
            this.waitingQuestion = null
            this.activeRunId = null
            this.activeStatus = null
            this.pendingSteer = null
            this.stoppedNotice = false
          }
        }
        this.confirmDeleteThreadId = null
        return true
      } catch (err) {
        if (err instanceof ApiError) {
          this.error = { code: err.code, message: gaErrorMessage(err.code, err.message) }
        } else {
          this.error = { code: 'UNKNOWN_ERROR', message: gaErrorMessage('UNKNOWN_ERROR') }
        }
        return false
      } finally {
        this.deletingThreadId = null
      }
    },
    async switchThreadIdle(threadId: string): Promise<void> {
      this.switchingThread = true
      try {
        const messages = await listGaMessages(threadId)
        await getGaThread(threadId)
        this.closeStream()
        this.threadId = threadId
        writeStored(GA_THREAD_KEY, threadId)
        writeStored(GA_RUN_KEY, null)
        this.messages = messages
        this.streamingText = ''
      this.streamGeneration = null
        this.activities = []
        this.currentStatus = null
        this.waitingQuestion = null
        this.approvalRequired = false
        this.stopSuccessorObservation()
        this.activeRunId = null
        this.activeStatus = null
        this.pendingSteer = null
        this.stoppedNotice = false
        this.lastSequence = 0
        this.cancelRequested = false
        this.connection = 'idle'
        this.historyOpen = false
        await this.refreshActivity()
      } catch (err) {
        if (err instanceof ApiError && err.code === 'THREAD_NOT_FOUND') {
          this.error = { code: 'THREAD_NOT_FOUND', message: '该会话已不存在，已为你保留当前会话。' }
          await this.loadThreads()
        } else if (err instanceof ApiError) {
          this.error = { code: err.code, message: gaErrorMessage(err.code, err.message) }
        }
      } finally {
        this.switchingThread = false
      }
    },
    async startNewConversation(): Promise<void> {
      if (this.sending || this.isRunning || this.steerSending) return
      this.closeStream()
      this.error = null
      this.historyOpen = false
      try {
        const created = await createGaThread()
        this.threadId = created.threadId
        writeStored(GA_THREAD_KEY, created.threadId)
        writeStored(GA_RUN_KEY, null)
        this.messages = []
        this.streamingText = ''
      this.streamGeneration = null
        this.activities = []
        this.currentStatus = null
        this.waitingQuestion = null
        this.approvalRequired = false
        this.lastUiAction = null
        this.pendingNavigation = null
        this.stopSuccessorObservation()
        this.activeRunId = null
        this.activeStatus = null
        this.lastSequence = 0
        this.cancelRequested = false
        this.connection = 'idle'
      } catch (err) {
        if (err instanceof ApiError) this.error = { code: err.code, message: gaErrorMessage(err.code, err.message) }
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
/** BUG-01 successor observation: monotonic token + owned timer. */
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

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => { setTimeout(resolve, ms) })
}
