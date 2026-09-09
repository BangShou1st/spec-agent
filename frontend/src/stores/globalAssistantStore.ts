import { defineStore } from 'pinia'
import { ApiError } from '@/api/client'
import {
  cancelGaRun,
  createGaRun,
  createGaThread,
  getGaRun,
  getGaThread,
  listGaEvents,
  listGaMessages,
  gaUiActionToRoute,
  type GaEventEnvelope,
  type GaMessage,
  type GaRun,
  type GaUiContext,
} from '@/api/globalAssistant'
import { openGaEventStream } from '@/api/globalAssistantEvents'
import { gaErrorMessage, gaToolDisplayName, gaArgsSummary } from '@/presentation/globalAssistantPresentation'

export const GA_THREAD_KEY = 'spec-agent:global-assistant:thread:v1'
export const GA_RUN_KEY = 'spec-agent:global-assistant:run:v1'
export const GA_PANEL_KEY = 'spec-agent:global-assistant:panel:v1'

export type GaToolState = 'running' | 'success' | 'failure'

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
        this.currentStatus = message
        return true
      }
      case 'ASSISTANT_DELTA': {
        const text = typeof payload.text === 'string' ? payload.text : ''
        if (!text) return false
        this.streamingText += text
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
        })
        return true
      }
      case 'TOOL_COMPLETED': {
        const capabilityId = typeof payload.capabilityId === 'string' ? payload.capabilityId : 'unknown'
        const summary = typeof payload.summary === 'string' ? payload.summary : null
        const target = findRunningActivity(this.activities, capabilityId)
        if (target) {
          target.state = 'success'
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
          state: 'success',
          summary,
          argsSummary: null,
          startedAt: typeof event.createdAt === 'string' ? event.createdAt : new Date().toISOString(),
          endedAt: typeof event.createdAt === 'string' ? event.createdAt : null,
          durationMs: null,
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
    activities: [] as GaToolActivity[],
    currentStatus: null as string | null,
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
  }),
  getters: {
    isRunning(state): boolean {
      return state.activeRunId !== null && (state.activeStatus === 'CREATED' || state.activeStatus === 'RUNNING')
    },
    hasMessages(state): boolean {
      return state.messages.length > 0 || state.streamingText.length > 0 || state.activities.length > 0
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
      if (!storedThread) return
      this.loadingThread = true
      try {
        await getGaThread(storedThread)
        this.threadId = storedThread
        this.messages = await listGaMessages(storedThread)
        if (storedRun) await this.recoverRun(storedRun)
      } catch (err) {
        if (err instanceof ApiError && err.code === 'THREAD_NOT_FOUND') {
          writeStored(GA_THREAD_KEY, null)
          writeStored(GA_RUN_KEY, null)
          this.threadId = null
          this.messages = []
          this.activeRunId = null
        } else if (err instanceof ApiError) {
          this.threadId = storedThread
          try { this.messages = await listGaMessages(storedThread) } catch { /* keep empty */ }
          this.error = { code: err.code, message: gaErrorMessage(err.code, err.message) }
        }
      } finally {
        this.loadingThread = false
      }
    },
    async ensureThread(): Promise<string> {
      if (this.threadId) return this.threadId
      const created = await createGaThread()
      this.threadId = created.threadId
      writeStored(GA_THREAD_KEY, created.threadId)
      return created.threadId
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
      projection.currentStatus = this.currentStatus
      projection.activities = this.activities
      projection.waitingQuestion = this.waitingQuestion
      projection.approvalRequired = this.approvalRequired
      projection.uiAction = this.lastUiAction
      const changed = projection.apply(event)
      void changed
      this.lastSequence = projection.lastSequence
      this.streamingText = projection.streamingText
      this.currentStatus = projection.currentStatus
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
      if (terminal.type === 'RUN_FAILED') {
        const code = terminal.errorCode ?? 'UNKNOWN_ERROR'
        this.error = { code, message: gaErrorMessage(code, terminal.reason ?? undefined) }
      }
      if (terminal.type === 'RUN_CANCELLED') {
        this.error = null
      }
      this.activeStatus = terminal.type === 'RUN_COMPLETED' ? 'COMPLETED' : terminal.type === 'RUN_FAILED' ? 'FAILED' : 'CANCELLED'
      this.currentStatus = null
      this.cancelRequested = false
      this.closeStream()
      this.connection = 'idle'
      writeStored(GA_RUN_KEY, null)
      const runId = this.activeRunId
      this.activeRunId = null
      void runId
      void this.reconcileMessages()
    },
    async reconcileMessages(): Promise<void> {
      // Backend messages are canonical for chat bubbles; tool cards and the
      // clarification question are durable timeline state for the last run.
      // They survive reconciliation and are cleared only on the next send.
      if (!this.threadId) return
      try {
        this.messages = await listGaMessages(this.threadId)
        this.streamingText = ''
      } catch { /* keep optimistic projection */ }
    },
    async sendMessage(text: string, uiContext: GaUiContext): Promise<void> {
      const message = text.trim()
      if (!message || this.sending || this.isRunning) return
      this.error = null
      this.sending = true
      this.draft = ''
      let threadId = this.threadId
      try {
        if (!threadId) threadId = await this.ensureThread()
        else {
          try { await getGaThread(threadId) } catch (err) {
            if (err instanceof ApiError && err.code === 'THREAD_NOT_FOUND') {
              writeStored(GA_THREAD_KEY, null)
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
        this.activities = []
        this.currentStatus = '正在处理…'
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
      const runId = this.activeRunId
      if (!runId || this.cancelRequested) return
      this.cancelRequested = true
      try {
        await cancelGaRun(runId)
      } catch (err) {
        this.cancelRequested = false
        if (err instanceof ApiError) {
          this.error = { code: err.code, message: gaErrorMessage(err.code, err.message) }
        }
      }
    },
    async startNewConversation(): Promise<void> {
      if (this.sending || this.isRunning) return
      this.closeStream()
      this.error = null
      try {
        const created = await createGaThread()
        this.threadId = created.threadId
        writeStored(GA_THREAD_KEY, created.threadId)
        writeStored(GA_RUN_KEY, null)
        this.messages = []
        this.streamingText = ''
        this.activities = []
        this.currentStatus = null
        this.waitingQuestion = null
        this.approvalRequired = false
        this.lastUiAction = null
        this.pendingNavigation = null
        this.activeRunId = null
        this.activeStatus = null
        this.lastSequence = 0
      } catch (err) {
        if (err instanceof ApiError) this.error = { code: err.code, message: gaErrorMessage(err.code, err.message) }
      }
    },
    closeStream(): void {
      closeStreamHandle()
    },
    $resetForTest(): void {
      this.closeStream()
      this.threadId = null
      this.messages = []
      this.activeRunId = null
      this.activeStatus = null
      this.lastSequence = 0
      this.streamingText = ''
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
    },
  },
})

let activeHandle: { close: () => void } | null = null
let activeController: AbortController | null = null

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
