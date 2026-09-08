import type { SubmitAnswerRequest } from '@/api/types'

/**
 * 纯恢复展示模型：把已有 store 状态映射为唯一恢复提示。
 *
 * 优先级冻结（高 → 低）：
 * 1. outcome unknown → 先同步，绝不重发
 * 2. answer saved → 继续生成，不要求重新填写
 * 3. proven-safe resubmit → 再次提交
 * 4. retryable model operation → 重试
 * 5. stale → 刷新/同步
 * 6. policy/permission/non-retryable → 只解释，不给无意义重试
 *
 * 只消费调用方传入的显式状态，不调 store action、不推断 mutation 是否落地。
 */

export type RecoveryAction =
  | 'reconcile-answer'
  | 'resume-answer'
  | 'resubmit-answer'
  | 'retry-model-operation'
  | 'refresh-workspace'

export interface RecoveryNoticeModel {
  kind: 'unknown' | 'saved' | 'resubmit' | 'retryable' | 'stale' | 'blocked'
  title: string
  message: string
  action: RecoveryAction | null
  actionLabel: string | null
}

export interface RecoveryPresentationInput {
  answerOutcomeUnknown?: boolean
  repairableAnswerId?: string | null
  resubmitAnswerPayload?: SubmitAnswerRequest | null
  /** 'ready' 可直接重试，'needs_reconcile'/'ambiguous' 先同步。 */
  manualRetryState?: 'ready' | 'needs_reconcile' | 'ambiguous' | null
  /** 已知 stale 的错误码（如 AGENT_RUN_OUTCOME_UNKNOWN、STALE）。 */
  staleErrorCode?: string | null
  /** 非重试类错误码（如 POLICY_DENIED、SOURCE_ROUTE_REQUIRED）。 */
  errorCode?: string | null
  /** 需要前往模型设置时，前端仍走普通错误条，不进恢复提示。 */
  requiresModelSettings?: boolean
}

const POLICY_BLOCKED_CODES = new Set([
  'POLICY_DENIED',
  'NOT_CONFIRMABLE',
  'SOURCE_ROUTE_REQUIRED',
  'FORK_DRAFT_RETRY_REQUIRES_ACTIVE_ROUTE',
  'ACTIVE_ROUTE_REQUIRED',
  'NO_ACTIVE_TIP_NODE',
  'PENDING_NODE_QUERY_NOT_ALLOWED',
  'SHARED_NODE_REQUIRES_ROUTE',
  'RECOVERY_AMBIGUOUS',
])

export function recoveryNoticeFromState(input: RecoveryPresentationInput): RecoveryNoticeModel | null {
  if (input.answerOutcomeUnknown === true) {
    return {
      kind: 'unknown',
      title: '提交结果暂时无法确认',
      message: '为了避免重复操作，请先同步最新状态。',
      action: 'reconcile-answer',
      actionLabel: '同步状态',
    }
  }
  if (input.repairableAnswerId) {
    return {
      kind: 'saved',
      title: '回答已经保存',
      message: '后续生成没有完成，不需要重新填写回答。',
      action: 'resume-answer',
      actionLabel: '继续生成',
    }
  }
  if (input.resubmitAnswerPayload) {
    return {
      kind: 'resubmit',
      title: '回答尚未保存',
      message: '已确认可以安全地再次提交。',
      action: 'resubmit-answer',
      actionLabel: '再次提交',
    }
  }
  if (input.manualRetryState === 'ready') {
    return {
      kind: 'retryable',
      title: '操作可以重试',
      message: '上一次运行没有完成，可以安全地再试一次。',
      action: 'retry-model-operation',
      actionLabel: '重新请求',
    }
  }
  if (input.manualRetryState === 'needs_reconcile' || input.manualRetryState === 'ambiguous') {
    return {
      kind: 'stale',
      title: '状态需要同步',
      message: '请先同步最新状态，确认后再决定是否重试。',
      action: 'refresh-workspace',
      actionLabel: '同步状态',
    }
  }
  if (input.staleErrorCode) {
    return {
      kind: 'stale',
      title: '状态需要同步',
      message: '当前显示的内容可能已过期，请同步最新状态。',
      action: 'refresh-workspace',
      actionLabel: '同步状态',
    }
  }
  if (input.errorCode && POLICY_BLOCKED_CODES.has(input.errorCode)) {
    return {
      kind: 'blocked',
      title: '当前操作无法继续',
      message: '请按提示处理后再试，不需要重复提交。',
      action: null,
      actionLabel: null,
    }
  }
  return null
}
