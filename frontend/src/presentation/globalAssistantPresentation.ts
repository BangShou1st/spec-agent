/** Presentation-only mapping for Global Assistant (never decides semantics). */
import { capabilityPresentation } from './capabilityPresentation'

export function gaToolDisplayName(capabilityId: string): string {
  return capabilityPresentation(capabilityId).actionLabel
}

const GA_ERROR_COPY: Record<string, string> = {
  PROJECT_NOT_FOUND: '该项目不存在，可能已被删除',
  TOOL_ARGUMENT_INVALID: '请求参数有误，请调整后重试',
  TOOL_EXECUTION_FAILED: '工具执行失败，请稍后再试',
  MODEL_UNAVAILABLE: '模型服务暂时不可用，请稍后再试',
  MODEL_INVALID_RESPONSE: '模型返回了无法识别的结果，请重新请求',
  RUN_STEP_LIMIT: '本次任务步骤已达上限，请换一种问法重试',
  RUN_CANCELLED: '本次任务已停止',
  GLOBAL_ASSISTANT_RUN_ACTIVE: '上一个请求仍在处理中，请稍候',
  GLOBAL_ASSISTANT_STEER_PENDING: '上一条调整正在生效，请稍后再发送新的要求',
  GLOBAL_ASSISTANT_RUN_STALE: '当前任务已更新，请刷新后作为新消息发送',
  GLOBAL_ASSISTANT_THREAD_ACTIVE: '当前任务完成或停止后可以切换会话',
  RUN_INTERRUPTED: '任务被中断，请重新发送',
  THREAD_NOT_FOUND: '当前会话已失效，已为你开始新会话',
  RUN_NOT_FOUND: '当前任务已不存在，请重新发送',
  MESSAGE_REQUIRED: '请输入要发送的内容',
  MESSAGE_TOO_LONG: '输入内容过长，请精简后重试',
  NETWORK_ERROR: '无法连接到服务，请检查网络后重试',
  UNKNOWN_ERROR: '操作失败，请稍后重试',
}

export function gaErrorMessage(code: string, fallback?: string): string {
  const normalized = (code || '').toUpperCase()
  const networkDetail = networkFailureCopy(fallback)
  if (networkDetail) return networkDetail
  if (GA_ERROR_COPY[normalized]) return GA_ERROR_COPY[normalized]
  if (fallback && fallback.trim().length > 0 && fallback.length < 200) return fallback
  return GA_ERROR_COPY.UNKNOWN_ERROR
}

/**
 * Exception names and socket-level phrases that mark a tool failure as a
 * network failure. The backend tool failures already carry the real cause
 * (transport exception + the outbound route it used); without this mapping
 * that reason is masked by the generic "请稍后再试" copy. Matched
 * case-insensitively against the raw reason text.
 */
const NETWORK_FAILURE_PATTERN =
  /TransportException|ConnectException|SocketTimeout|SocketException|UnknownHost|SSLHandshake|SSLException|NoRouteToHost|Connection (?:timed out|refused|reset)|Read timed out|connect timed out/i

/**
 * Network failures surface the backend's real reason instead of the generic
 * copy, so the user sees "网络连接失败：Git import failed: TransportException
 * via local proxy 127.0.0.1:7897 (Connection timed out)" rather than a dead
 * end. Returns null for anything that is not a network failure.
 */
function networkFailureCopy(fallback?: string): string | null {
  const text = (fallback ?? '').trim()
  if (!text || !NETWORK_FAILURE_PATTERN.test(text)) return null
  const bounded = text.length > 160 ? text.slice(0, 160) + '…' : text
  return '网络连接失败：' + bounded
}

/** Compact single-line summary for tool arguments (sanitized, conservative). */
export function gaArgsSummary(args: unknown): string | null {
  if (!args || typeof args !== 'object') return null
  const record = args as Record<string, unknown>
  const picked: string[] = []
  for (const key of ['query', 'title', 'keyword', 'projectId', 'limit']) {
    const value = record[key]
    if (typeof value === 'string' && value.trim().length > 0) {
      const text = value.trim()
      picked.push(text.length > 42 ? text.slice(0, 42) + '…' : text)
    } else if (typeof value === 'number') {
      picked.push(String(value))
    }
    if (picked.length >= 2) break
  }
  if (picked.length === 0) return null
  return picked.join(' · ')
}
/** Optimistic send-time status, replaced once real runtime evidence arrives. */
export const GA_SENDING_STATUS = '正在处理…'
/** Phase label used once streamed answer text is visibly generating. */
export const GA_GENERATING_STATUS = '正在生成回答…'
/**
 * Presentation-only mapping for backend runtime status strings.
 * Keyed by the frozen backend message contract, never by prompt text,
 * capability branches in views, or per-scenario special cases.
 * Unknown messages pass through verbatim so information is never hidden.
 */
/** Calendar-day distance ignoring time-of-day, DST-safe (local calendar). */
function dayDistance(from: Date, to: Date): number {
  const a = new Date(from.getFullYear(), from.getMonth(), from.getDate()).getTime()
  const b = new Date(to.getFullYear(), to.getMonth(), to.getDate()).getTime()
  return Math.round((b - a) / 86400000)
}

function clockOf(date: Date): string {
  const hours = String(date.getHours()).padStart(2, '0')
  const minutes = String(date.getMinutes()).padStart(2, '0')
  return hours + ':' + minutes
}

/**
 * Timestamp label for one conversation message. Same-day messages keep the
 * compact clock reading; anything older carries the actual date, so a
 * conversation that spans days is never ambiguous. Returns '' for missing or
 * unparseable input so callers hide the row instead of rendering garbage.
 *
 * Pure and `now`-injectable: the label is decided only by the two dates.
 */
export function gaMessageTimeLabel(iso?: string | null, now: Date = new Date()): string {
  if (!iso) return ''
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  const clock = clockOf(date)
  const age = dayDistance(date, now)
  if (age === 0) return clock
  if (age === 1) return '昨天 ' + clock
  const monthDay = date.getMonth() + 1 + '月' + date.getDate() + '日'
  if (date.getFullYear() === now.getFullYear()) {
    return monthDay + ' ' + clock
  }
  return date.getFullYear() + '年' + monthDay + ' ' + clock
}

/** A starter prompt offered in the empty state. Label and prompt live here so
 * no component hard-codes copy and a new starter only needs one entry. */
export interface GaSuggestion {
  label: string
  prompt: string
}

/**
 * Starters are chosen to match what the tool whitelist can actually do
 * (project search, project summary read, navigation) — never a capability the
 * assistant cannot execute.
 */
export const GA_EMPTY_SUGGESTIONS: readonly GaSuggestion[] = [
  { label: '找项目', prompt: '帮我找一下正在做的项目' },
  { label: '读概要', prompt: '读取最近一个项目的概要' },
  { label: '去页面', prompt: '打开项目列表' },
]

const GA_STATUS_COPY: Record<string, string> = {
  'Creating project': '正在创建项目…',
  'Searching projects': '正在搜索项目…',
  'Listing recent projects': '正在列出最近项目…',
  'Reading project summary': '正在读取项目概要…',
  'Working': '正在处理…',
  'Composing answer': '正在生成回答…',
}
export function gaStatusMessage(message: string): string {
  if (!message) return message
  return GA_STATUS_COPY[message] ?? message
}
