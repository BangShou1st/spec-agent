/** Stable product copy for safe API error codes. Provider payloads and raw
 * backend messages never become user-facing text through this mapper. */
const ERROR_COPY: Record<string, string> = {
  NOT_CONFIGURED: '尚未配置模型，请前往模型设置。',
  AUTHENTICATION: '当前 API Key 已失效，请更换 API Key。',
  RATE_LIMITED: '模型服务当前请求较多，请稍后再试。',
  TIMEOUT: '模型服务响应超时，请稍后再试。',
  CONNECTION: '无法连接到 OpenCode，请检查网络后重试。',
  NETWORK_ERROR: '无法连接到 OpenCode，请检查网络后重试。',
  SERVER_ERROR: '模型服务暂时不可用，请稍后再试。',
  INVALID_MODEL: '当前模型已不可用，请重新选择。',
  INVALID_RESPONSE: '模型返回了无法识别的结果，请重新请求。',
  EMPTY_CONTENT: '模型没有返回可用内容，请重新请求。',
  MODEL_CONTRACT_REJECTED: '模型输出未通过校验，请重新请求。',
  ACTIVE_ROUTE_REQUIRED: '当前没有可用路线，无法起草问题。请刷新状态后重试。',
  PENDING_NODE_QUERY_NOT_ALLOWED: '临时运行卡片不是可查询的节点，请选择真实节点。',
  UNKNOWN_ERROR: '操作失败，请稍后重试。',
  INTERNAL_ERROR: '操作失败，请稍后重试。',
  INTERNAL_INVARIANT_VIOLATION: '工作区状态出现异常，请刷新状态。',
}

function stableCode(code: string): string {
  const normalized = code.toUpperCase()
  if (normalized === 'MODEL_PROVIDER_NOT_CONFIGURED') return 'NOT_CONFIGURED'
  if (normalized.includes('AUTHENTICATION')) return 'AUTHENTICATION'
  if (normalized.includes('RATE_LIMITED')) return 'RATE_LIMITED'
  if (normalized.includes('TIMEOUT')) return 'TIMEOUT'
  if (normalized === 'NETWORK_ERROR') return 'NETWORK_ERROR'
  if (normalized.includes('CONNECTION') || normalized.includes('MODEL_PROVIDER_UNREACHABLE')) {
    return 'CONNECTION'
  }
  if (normalized.includes('SERVER_ERROR') || normalized === 'MODEL_PROVIDER_ERROR') return 'SERVER_ERROR'
  if (normalized.includes('INVALID_MODEL')) return 'INVALID_MODEL'
  if (normalized.includes('INVALID_RESPONSE')) return 'INVALID_RESPONSE'
  if (normalized.includes('EMPTY_CONTENT')) return 'EMPTY_CONTENT'
  if (normalized.includes('MODEL_CONTRACT_REJECTED')) return 'MODEL_CONTRACT_REJECTED'
  return normalized
}

export function productErrorMessage(code: string, _safeFallback?: string): string {
  return ERROR_COPY[stableCode(code)] ?? '操作失败，请稍后重试。'
}

export function requiresModelSettings(code: string): boolean {
  return ['NOT_CONFIGURED', 'AUTHENTICATION', 'INVALID_MODEL'].includes(stableCode(code))
}

export type ModelFailureDisposition = 'retryable' | 'unknown' | 'none'

/**
 * Classifies only model/provider failures that are safe to offer as a manual
 * model retry. Network outcome is deliberately separate: a request with an
 * unknown result must reconcile canonical state before any new mutation.
 */
export function classifyModelFailure(code: string, status?: number): ModelFailureDisposition {
  const normalized = stableCode(code)
  if (status === 0 || normalized === 'NETWORK_ERROR') return 'unknown'
  if ([
    'RATE_LIMITED',
    'TIMEOUT',
    'CONNECTION',
    'SERVER_ERROR',
    'INVALID_RESPONSE',
    'EMPTY_CONTENT',
    'MODEL_CONTRACT_REJECTED',
  ].includes(normalized)) {
    return 'retryable'
  }
  return 'none'
}
