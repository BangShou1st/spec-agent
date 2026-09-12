/** Presentation-only mapping for Global Assistant (never decides semantics). */
import { capabilityPresentation } from './capabilityPresentation'

export function gaToolDisplayName(capabilityId: string): string {
  return capabilityPresentation(capabilityId).actionLabel
}

const GA_ERROR_COPY: Record<string, string> = {
  PROJECT_NOT_FOUND: '该项目不存在，可能已被删除。',
  TOOL_ARGUMENT_INVALID: '请求参数有误，请调整后重试。',
  TOOL_EXECUTION_FAILED: '工具执行失败，请稍后再试。',
  MODEL_UNAVAILABLE: '模型服务暂时不可用，请稍后再试。',
  MODEL_INVALID_RESPONSE: '模型返回了无法识别的结果，请重新请求。',
  RUN_STEP_LIMIT: '本次任务步骤已达上限，请换一种问法重试。',
  RUN_CANCELLED: '本次任务已停止。',
  GLOBAL_ASSISTANT_RUN_ACTIVE: '上一个请求仍在处理中，请稍候。',
  GLOBAL_ASSISTANT_STEER_PENDING: '上一条调整正在生效，请稍后再发送新的要求。',
  GLOBAL_ASSISTANT_RUN_STALE: '当前任务已更新，请刷新后作为新消息发送。',
  GLOBAL_ASSISTANT_THREAD_ACTIVE: '当前任务完成或停止后可以切换会话。',
  RUN_INTERRUPTED: '任务被中断，请重新发送。',
  THREAD_NOT_FOUND: '当前会话已失效，已为你开始新会话。',
  RUN_NOT_FOUND: '当前任务已不存在，请重新发送。',
  MESSAGE_REQUIRED: '请输入要发送的内容。',
  MESSAGE_TOO_LONG: '输入内容过长，请精简后重试。',
  NETWORK_ERROR: '无法连接到服务，请检查网络后重试。',
  UNKNOWN_ERROR: '操作失败，请稍后重试。',
}

export function gaErrorMessage(code: string, fallback?: string): string {
  const normalized = (code || '').toUpperCase()
  if (GA_ERROR_COPY[normalized]) return GA_ERROR_COPY[normalized]
  if (fallback && fallback.trim().length > 0 && fallback.length < 200) return fallback
  return GA_ERROR_COPY.UNKNOWN_ERROR
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
