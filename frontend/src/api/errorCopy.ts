/** Stable product copy for safe API error codes. Provider payloads and raw
 * backend messages never become user-facing text through this mapper. */
const ERROR_COPY: Record<string, string> = {
  NOT_CONFIGURED: '尚未配置模型，请前往模型设置',
  AUTHENTICATION: '当前 API Key 已失效，请更换 API Key',
  RATE_LIMITED: '模型服务当前请求较多，请稍后再试',
  TIMEOUT: '模型服务响应超时，请稍后再试',
  CONNECTION: '无法连接到 OpenCode，请检查网络后重试',
  NETWORK_ERROR: '无法连接到 OpenCode，请检查网络后重试',
  SERVER_ERROR: '模型服务暂时不可用，请稍后再试',
  INVALID_MODEL: '当前模型已不可用，请重新选择',
  INVALID_RESPONSE: '模型返回了无法识别的结果，请重新请求',
  EMPTY_CONTENT: '模型没有返回可用内容，请重新请求',
  MODEL_CONTRACT_REJECTED: '模型输出未通过校验，请重新请求',
  ACTIVE_ROUTE_REQUIRED: '当前没有可用路线，无法起草问题。请刷新状态后重试',
  PENDING_NODE_QUERY_NOT_ALLOWED: '临时运行卡片不是可查询的节点，请选择真实节点',
  UNKNOWN_ERROR: '操作失败，请稍后重试',
  INTERNAL_ERROR: '操作失败，请稍后重试',
  INTERNAL_INVARIANT_VIOLATION: '工作区状态出现异常，请刷新状态',
  SKILL_IMPORT_REJECTED: 'Skill 导入被拒绝，请检查文件或地址后重试',
  SKILL_RESOURCE_REJECTED: 'Skill 资源无法读取，请稍后再试',
  CONNECTION_COMMAND_REJECTED: '连接操作失败，请稍后再试',
  CONNECTION_NOT_FOUND: '该连接不存在，可能已被删除',
  VALIDATION_ERROR: '输入有误，请检查后重试',
  PROJECT_TITLE_ALREADY_EXISTS: '已存在同名项目，请换一个名称',
  PROJECT_NOT_FOUND: '该项目不存在，可能已被删除',
  // ---- 运行时状态冲突类：文案必须带操作指引，指引不能只说"重试" ----
  RUNTIME_CONFLICT: '工作区状态已发生变化，本次操作无法完成。请刷新画布，找到最新的待回答问题节点后重试',
  NO_ACTIVE_ROUTE: '当前没有运行中的路线。请在画布上激活一条路线，或新建问题后再试',
  ROUTE_NOT_OPEN: '该路线已结束，无法继续操作。请在画布上找到最新的待回答问题节点',
  ROUTE_NOT_ACTIVATABLE: '该路线已被更新的路线接替，无法切换为当前路线。请在画布上找到最新的待回答问题节点直接作答',
  NO_ACTIVE_TIP_NODE: '当前路线没有可回答的问题节点，无法生成规格。请先在路线末尾回答一个问题',
  ANSWER_ALREADY_FINALIZED: '该问题已有确认的回答，不能重复作答。可在节点详情中查看历史回答',
  AGENT_RUN_TARGET_STALE: '操作目标已过期：画布在此期间发生了变化。请刷新画布后针对最新的问题节点重试',
  AGENT_RUN_FAILED: '本次生成未完成。请查看对应节点的运行状态后重试',
  REGENERATE_TARGET_REQUIRED: '换一个问法需要指定来源路线和节点，请从具体问题节点的操作中发起',
  REGENERATE_ROOT_NOT_SUPPORTED: '起始问题不支持换一个问法，请从后续的问题节点发起',
  RELATION_DEPENDENCY_CYCLE: '该关系会形成循环依赖，无法创建。请调整关系的方向或类型',
  NODE_NOT_DETACHABLE: '只有路线最末端的节点可以断开。请从路线末端的节点发起断开',
  CONNECT_NOT_AT_TIP: '节点只能接入路线最末端，不能挂到历史节点上。请连到当前末端的节点',
  UNANSWERED_QUESTION_HAS_CHILD: '当前问题还没有回答，不能在它下面继续追加内容。请先回答最新问题',
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

export function productErrorMessage(code: string, safeFallback?: string): string {
  const canned = ERROR_COPY[stableCode(code)]
  if (canned) return canned
  // 未知错误码（多为前端预检的动态引导文案，如"只能接入路线末端"）直接
  // 展示安全 message：DisplayError.message 要么是前端手写引导，要么是后端
  // 已脱敏的 envelope 文案，绝不是原始响应体。
  if (safeFallback && safeFallback.trim().length > 0 && safeFallback !== '操作失败，请稍后重试') {
    return safeFallback
  }
  return '操作失败，请稍后重试'
}

export function managementErrorMessage(code: string, safeBackendMessage?: string): string {
  const normalized = code.toUpperCase()
  if (
    normalized === 'CONNECTION_COMMAND_REJECTED' ||
    normalized === 'VALIDATION_ERROR' ||
    normalized === 'SKILL_IMPORT_REJECTED' ||
    normalized === 'SKILL_RESOURCE_REJECTED'
  ) {
    if (safeBackendMessage && safeBackendMessage.trim().length > 0) {
      return safeBackendMessage
    }
  }
  return productErrorMessage(code)
}

/**
 * Copy for the projects page. Backend messages are English by contract, so the
 * codes this page can surface are mapped to product copy here; anything else
 * keeps the backend message rather than being flattened into a generic one.
 */
export function projectErrorMessage(code: string, backendMessage: string): string {
  const normalized = code.toUpperCase()
  const copy = ERROR_COPY[normalized]
  if (normalized === 'PROJECT_TITLE_ALREADY_EXISTS' || normalized === 'PROJECT_NOT_FOUND') {
    return copy
  }
  return backendMessage
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
