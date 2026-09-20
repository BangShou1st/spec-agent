/**
 * 纯产品文案映射：Runtime phase / actionFamily → 面向用户的中文标签。
 *
 * 只做查表映射，不读 store、不推断链语义。未知输入一律回退到通用文案，
 * 绝不把 raw phase / actionFamily 拼进默认 UI。
 */

/** 产品化 Agent 运行状态（一句话，不暴露 raw runtime phase）。 */
const PHASE_LABELS: Record<string, string> = {
  CREATED: '正在准备…',
  SNAPSHOT_BUILT: '正在分析上下文',
  STATE_UPDATING: '正在整理需求',
  STATE_UPDATED: '需求状态已更新',
  DECIDING: '正在规划下一步',
  PROPOSAL_CREATED: '正在执行',
  ARTIFACT_GENERATING: '正在生成产物…',
  EXECUTING: '正在执行',
  WAITING_USER: '等待你的输入',
  AWAITING_APPROVAL: '等待你的确认',
  COMPLETED: '已完成',
  FAILED: '需要处理',
  STALE: '需要处理',
  // 兼容 NodeQuery 轮询的中间/终态（非 AgentRunPhase，但会流经同一展示层）。
  RUNNING: '正在继续处理',
  ACCEPTED: '已完成',
  REJECTED: '已完成',
  POLICY_DENIED: '需要处理',
  NOT_CONFIRMABLE: '需要处理',
}

/** 通用未知回退：固定文案，不拼接 raw phase。 */
export const UNKNOWN_PHASE_LABEL = '处理中…'

/** 未知 actionFamily 回退。 */
export const UNKNOWN_ACTION_LABEL = '执行操作'

/** 后端 ActionFamily 全集的可读标签（与 backend ActionFamily 枚举对应）。 */
const ACTION_LABELS: Record<string, string> = {
  CREATE_NODE: '创建节点',
  UPDATE_NODE: '更新节点',
  CONNECT_NODE: '连接节点',
  CREATE_ROUTE: '创建路线',
  REQUEST_USER_INPUT: '请求你的输入',
  RESPOND_TO_USER: '回复你',
  INVOKE_CAPABILITY: '运行能力',
  GENERATE_ARTIFACT: '生成产物',
  WAIT: '等待',
}

export function agentPhaseLabel(phase: string | null | undefined): string {
  if (!phase) return UNKNOWN_PHASE_LABEL
  return PHASE_LABELS[phase] ?? UNKNOWN_PHASE_LABEL
}

export function agentActionLabel(actionFamily: string | null | undefined): string {
  if (!actionFamily) return UNKNOWN_ACTION_LABEL
  return ACTION_LABELS[actionFamily] ?? UNKNOWN_ACTION_LABEL
}

/** 后端 run operation → pending 卡的进行中文案。 */
const OPERATION_PROGRESS_LABELS: Record<string, string> = {
  DRAFT_QUESTION: '正在生成下一步问题…',
  ANSWER_TIP: '正在生成回答…',
  RESUME_ANSWER: '正在生成回答…',
  REGENERATE_NODE: '正在重新生成该节点的问题…',
  GENERATE_ARTIFACT: '正在生成规格文档…',
}

/** 未知 operation 回退：固定文案，不拼接 raw operation。 */
export const UNKNOWN_OPERATION_PROGRESS_LABEL = '正在处理…'

export function agentOperationProgressLabel(operation: string | null | undefined): string {
  if (!operation) return OPERATION_PROGRESS_LABELS.DRAFT_QUESTION
  return OPERATION_PROGRESS_LABELS[operation] ?? UNKNOWN_OPERATION_PROGRESS_LABEL
}

/** run operation → pending 卡的失败文案。 */
const OPERATION_FAILURE_LABELS: Record<string, string> = {
  DRAFT_QUESTION: '下一步问题生成失败',
  ANSWER_TIP: '回答生成失败',
  RESUME_ANSWER: '回答生成失败',
  REGENERATE_NODE: '重新生成失败',
  GENERATE_ARTIFACT: '规格文档生成失败',
}

export const UNKNOWN_OPERATION_FAILURE_LABEL = '生成失败，请重试'

export function agentOperationFailureLabel(operation: string | null | undefined): string {
  if (!operation) return OPERATION_FAILURE_LABELS.DRAFT_QUESTION
  return OPERATION_FAILURE_LABELS[operation] ?? UNKNOWN_OPERATION_FAILURE_LABEL
}
