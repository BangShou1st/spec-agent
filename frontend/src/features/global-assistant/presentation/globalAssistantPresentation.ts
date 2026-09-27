// 文件名:globalAssistantPresentation.ts
// 用途:全局助手的展示层映射(只做展示,不决定语义):工具显示名、错误码到用户文案、
//       网络失败原因透出、工具参数摘要、发送/生成阶段状态文案、消息时间标签、
//       空态建议提问与后端运行状态文案的翻译。

/** 全局助手的展示层专用映射(绝不决定语义)。 */
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
 * 标记工具失败属于网络失败的异常名与套接字层面的短语。
 * 后端的工具失败本身携带真实原因(传输异常 + 所使用的出站路由);
 * 若不做这个映射,该原因会被通用的"请稍后再试"文案掩盖。
 * 对原始原因文本做大小写不敏感匹配。
 */
const NETWORK_FAILURE_PATTERN =
  /TransportException|ConnectException|SocketTimeout|SocketException|UnknownHost|SSLHandshake|SSLException|NoRouteToHost|Connection (?:timed out|refused|reset)|Read timed out|connect timed out/i

/**
 * 网络失败时透出后端的真实原因,而不是通用文案,让用户看到
 * "网络连接失败:Git import failed: TransportException via local proxy
 * 127.0.0.1:7897 (Connection timed out)" 这样的具体信息,而非死胡同。
 * 非网络失败返回 null。
 */
function networkFailureCopy(fallback?: string): string | null {
  const text = (fallback ?? '').trim()
  if (!text || !NETWORK_FAILURE_PATTERN.test(text)) return null
  const bounded = text.length > 160 ? text.slice(0, 160) + '…' : text
  return '网络连接失败：' + bounded
}

/** 工具参数的单行紧凑摘要(已清洗,保守截断)。 */
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
/** 乐观的发送期状态,真实运行事件到达后即被替换。 */
export const GA_SENDING_STATUS = '正在处理…'
/** 流式回答文本开始可见生成时的阶段标签。 */
export const GA_GENERATING_STATUS = '正在生成回答…'
/**
 * 后端运行状态字符串的展示层映射。
 * 按(已冻结的)后端消息契约做键,绝不按 prompt 文本、视图内的 capability
 * 分支或按场景特判;未知消息原样透出,不隐藏任何信息。
 */
/** 忽略一天内时刻的日历天数差,对夏令时安全(按本地日历)。 */
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
 * 单条会话消息的时间戳标签。当天的消息只显示紧凑时刻;更早的消息携带真实日期,
 * 跨天的会话不会产生歧义。输入缺失或无法解析时返回 '',
 * 调用方据此隐藏该行而不是渲染乱码。
 *
 * 纯函数,可注入 `now`:标签只由两个日期决定。
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

/** 空态展示的建议提问。label 与 prompt 都定义在此,
 * 组件不做文案硬编码,新增建议只需加一条。 */
export interface GaSuggestion {
  label: string
  prompt: string
}

/**
 * 建议提问只挑选工具白名单真正能做的事
 * (项目搜索、项目概要读取、页面导航),绝不承诺助手做不到的能力。
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
