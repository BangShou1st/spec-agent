// 文件名:agentRuns.ts
// 用途:AgentRun 异步命令 + 轮询 API。
// 前端从不在别处手拼运行载荷:回答/修复(以及后续的问题/Spec/重生成)等变更
// 全部经由这些显式方法,与后端契约一一对应。HTTP 命令立即返回 runId;
// 完成与否通过轮询运行读取端点直到终态来观察。不使用 WebSocket/SSE。

import { apiClient } from '@/shared/http/client'

export type AgentRunOperation =
  | 'ANSWER_TIP'
  | 'RESUME_ANSWER'
  | 'DRAFT_QUESTION'
  | 'GENERATE_ARTIFACT'
  | 'REGENERATE_NODE'

/**
 * 后端 run.operation 的稳定契约值集中地(R5-A 的闭合)。
 *
 * operation 与 triggerType 是两个不同字段:规格生成的 run 是
 * triggerType=GENERATE_SPEC、operation=GENERATE_ARTIFACT,失败清单
 * (UnresolvedFailureView)返回的是 operation。分发/筛选必须以这里的
 * 常量为准,绝不在调用点手写字符串(避免与 triggerType 混用)。
 */
export const SPEC_GENERATION_OPERATION = 'GENERATE_ARTIFACT'
export const DRAFT_QUESTION_OPERATION = 'DRAFT_QUESTION'
export const NODE_QUERY_OPERATION = 'NODE_QUERY'

/** 起草/续跑家族:失败渲染为源节点下游的占位卡,不绑定源节点恢复栏。 */
export const DRAFT_FAMILY_OPERATIONS: ReadonlySet<string> = new Set([
  DRAFT_QUESTION_OPERATION,
  'CONTINUE',
])

/** GET /agent-runs/{runId} 返回的粗粒度生命周期状态。 */
export type AgentRunStatus =
  | 'created'
  | 'running'
  | 'completed'
  | 'failed'

/** 真实持久化的运行阶段(AgentRunPhase);进度文案由它们派生。 */
export const AGENT_RUN_PHASES = [
  'CREATED',
  'SNAPSHOT_BUILT',
  'STATE_UPDATING',
  'STATE_UPDATED',
  'DECIDING',
  'PROPOSAL_CREATED',
  'ARTIFACT_GENERATING',
  'AWAITING_APPROVAL',
  'EXECUTING',
  'WAITING_USER',
  'COMPLETED',
  'FAILED',
  'STALE',
] as const

export type AgentRunPhase = (typeof AGENT_RUN_PHASES)[number]

const TERMINAL_STATUSES: ReadonlySet<string> = new Set(['completed', 'failed'])

/** 轮询节奏:有界且克制——绝无紧凑的死循环。 */
export const AGENT_RUN_POLL_INTERVAL_MS = 1500

/** 单次运行的轮询上限;超出即按 FAILED 处理以驱动恢复 UX。 */
export const AGENT_RUN_MAX_POLLS = 120

export interface AgentRunCreated {
  runId: string
  operation: string
  phase: string
}

export interface AgentRunView {
  runId: string
  projectId: string
  /** 游离节点查询等无路线运行没有 route;后端输出 null。 */
  routeId: string | null
  operation: string
  status: AgentRunStatus
  phase: AgentRunPhase | string
  producedNodeId: string | null
  producedAnswerId: string | null
  producedPatchId: string | null
  producedSpecSnapshotId: string | null
  /** 直接的自动续跑子运行 id;尚不存在时缺省。 */
  childRunId?: string | null
  /** 本次运行的持久化续跑检查仍在进行中时为 true。 */
  continuationPending?: boolean
  /** 最新的持久化 RESPOND_MESSAGE 文本;运行从未回应过时缺省。 */
  respondMessage?: string | null
  /** 白名单化的进度读取模型;旧载荷上缺省。 */
  progress?: RunProgressView | null
}

/** 单个白名单化的进度步骤(后端 RunProgressView.Step)。 */
export interface RunProgressStep {
  sequence: number
  phase: string
  event: string
  summary: string | null
  items: string[] | null
  at: string
}

/** 白名单化的运行进度:只含编排好的摘要,绝不含原始载荷。 */
export interface RunProgressView {
  phase: string | null
  summary: string | null
  steps: RunProgressStep[]
}

export interface CreateAgentRunPayload {
  operation: AgentRunOperation
  /** 被回答/重新生成的节点;省略时后端回退到激活路线的末端问题。 */
  nodeId?: string | null
  /** 多选题的全量选择（用户顺序）；单选题仍走 selectedOptionId。 */
  selectedOptionIds?: string[] | null
  /**
   * 本次运行的显式路线。
   *
   * REGENERATE_NODE 必填(作为重生成的来源路线)。对 ANSWER_TIP /
   * RESUME_ANSWER / DRAFT_QUESTION / GENERATE_ARTIFACT 也可选:提供时该运行
   * 在整个生命周期内绑定到这条路线("EXPLICIT" 路线模式),这正是多条路线
   * 能独立回答与生成的原因。缺省时运行完全遵循项目的 Active 路线——
   * 包括运行仍在排队而该指针移动时直接失败(fail closed)。
   */
  sourceRouteId?: string | null
  selectedOptionId?: string | null
  freeText?: string | null
  /** RESUME_ANSWER 必填:被续跑的持久化 Answer id。 */
  answerId?: string | null
  /**
   * 一次用户动作尝试的稳定身份。结果未知(网络丢失、超时、响应丢失)后的重试
   * 必须复用同一个 key,让后端返回已创建的运行而不是再创建一个。
   * 真正全新的用户动作才生成新的 key。
   */
  idempotencyKey?: string | null
}

export function createAgentRun(
  projectId: string,
  payload: CreateAgentRunPayload,
): Promise<AgentRunCreated> {
  return apiClient.post<AgentRunCreated>(`/projects/${projectId}/agent-runs`, {
    operation: payload.operation,
    nodeId: payload.nodeId ?? null,
    sourceRouteId: payload.sourceRouteId ?? null,
    selectedOptionId: payload.selectedOptionId ?? null,
    selectedOptionIds: payload.selectedOptionIds ?? null,
    freeText: payload.freeText ?? null,
    answerId: payload.answerId ?? null,
    idempotencyKey: payload.idempotencyKey ?? null,
  })
}

export function getAgentRun(projectId: string, runId: string): Promise<AgentRunView> {
  return apiClient.get<AgentRunView>(`/projects/${projectId}/agent-runs/${runId}`)
}


/** 服务端判定的未解决失败(任务级恢复契约的读侧)。字段与后端
 * UnresolvedFailureView 一一对应;availableAction 决定按钮文案与行为。 */
export interface UnresolvedFailure {
  runId: string
  projectId: string
  operation: string
  routeId: string | null
  sourceNodeId: string | null
  reasonCode: string
  reasonSummary: string | null
  availableAction:
    | 'RETRY_GENERATION'
    | 'CONTINUE_PROCESSING'
    | 'RETRY_REGENERATE'
    | 'RETRY_SPEC'
    | 'RETRY_NODE_QUERY'
    | 'GO_TO_MODEL_SETTINGS'
    | 'STALE'
  actionLabel: string
  stale: boolean
  retryRunId: string | null
  retryStatus: string | null
  createdAt: string
}

/** 项目内未解决失败清单:页面加载/刷新时用于重建失败恢复入口。 */
export function listUnresolvedRuns(projectId: string): Promise<UnresolvedFailure[]> {
  return apiClient.get<UnresolvedFailure[]>(`/projects/${projectId}/agent-runs/unresolved`)
}

/** 从失败任务身份重试:服务端从持久化事实还原意图并派发领域命令;
 * 幂等——同一失败任务的并发/重复重试返回同一个 run。 */
export function retryAgentRun(projectId: string, failedRunId: string): Promise<AgentRunCreated> {
  return apiClient.post<AgentRunCreated>(
    `/projects/${projectId}/agent-runs/${failedRunId}/retry`, {})
}

/** 项目所有非终态的运行;页面刷新后用于重建在途运行注册表。 */
export function listActiveRuns(projectId: string): Promise<AgentRunView[]> {
  return apiClient.get<AgentRunView[]>(`/projects/${projectId}/agent-runs/active`)
}

export function isTerminalRunStatus(status: string): boolean {
  return TERMINAL_STATUSES.has(status)
}
