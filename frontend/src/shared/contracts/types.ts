// 文件名:types.ts
// 用途:前后端共享的 TypeScript 契约类型:冻结的 Phase 6 后端 API 加 Phase 7.1 的 UI 辅助读取端点,类型镜像真实后端 DTO 字段,不经过 schema 生成器推断。
/*
 * 冻结的 Phase 6 后端 API 加上唯一的 Phase 7.1 UI 辅助读取端点的
 * TypeScript 契约。类型镜像真实后端 DTO 响应字段;不从 schema 生成器
 * 推断任何东西。
 */

/** 仅路线生命周期。`active` 不是生命周期状态。 */
export type RouteLifecycleStatus = 'open' | 'superseded' | 'archived' | 'deleted'

/** 后端断言状态;前端绝不自行推断状态。 */
export type ClaimStatus = 'confirmed' | 'assumed' | 'unresolved' | 'rejected'

export interface ApiFieldError {
  field: string
  message: string
}

export interface ApiErrorPayload {
  code: string
  message: string
  timestamp?: string
  errors?: ApiFieldError[]
  /** 面向可操作冲突的有界、非敏感恢复身份。 */
  details?: Record<string, string>
}

export interface ProjectSummaryResponse {
  id: string
  title: string
  activeRouteId: string | null
  createdAt: string
  updatedAt: string
}

export interface ProjectResponse {
  id: string
  title: string
  activeRouteId: string | null
  defaultProfileId: string | null
  createdAt: string
  updatedAt: string
}

export interface CreateProjectRequest {
  title: string
}

export interface OpenCodeSettingsStatus {
  configured: boolean
  maskedKey: string | null
  selectedModel: string | null
}

export interface OpenCodeProbeResponse {
  /** 提供方当前暴露的全部模型 id(免费与付费)。
   *  旧后端省略此字段;store 回退到 freeModels。 */
  allModels?: string[]
  /** 免费子集;始终存在。 */
  freeModels: string[]
}

export interface OpenCodeModelChangeRequest {
  selectedModel: string
}

export interface RouteResponse {
  id: string
  projectId: string
  rootNodeId: string | null
  tipNodeId: string | null
  lifecycleStatus: RouteLifecycleStatus
  label: string | null
  createdFromNodeId: string | null
  supersedesRouteId: string | null
  replacementOfNodeId: string | null
  branchType?: 'fork' | 'reanswer' | 'regenerate' | 'continuation' | null
  sourceRouteId?: string | null
  branchAtNodeId?: string | null
  createdAt: string
  updatedAt: string
  /** 后端派生:读取时 routeId === Project.activeRouteId。 */
  isActive: boolean
}

export interface NodeOptionResponse {
  id: string
  label: string
  impact: string | null
  /** 模型基于上下文的推荐（只是建议，绝不预选中）。 */
  recommended: boolean
}

export interface NodeResponse {
  id: string
  projectId: string
  parentNodeId: string | null
  supersedesNodeId: string | null
  question: string
  purpose: string | null
  options: NodeOptionResponse[]
  allowFreeAnswer: boolean
  allowMultiSelect: boolean
  createdAt: string
}

export interface ActiveProjectStateResponse {
  project: ProjectResponse
  activeRoute: RouteResponse | null
  activeNode: NodeResponse | null
}

export interface AgentRunResponse {
  id: string
  projectId: string
  routeId: string | null
  triggerType: string
  inputNodeId: string | null
  contextSnapshotId: string | null
  producedNodeId: string | null
  producedAnswerId: string | null
  producedPatchId: string | null
  producedSpecSnapshotId: string | null
  status: string
  traceSteps: string[]
  createdAt: string
  completedAt: string | null
}

export interface AnswerResponse {
  id: string
  projectId: string
  routeId: string
  nodeId: string
  selectedOptionId: string | null
  freeText: string | null
  createdAt: string
}

export interface ClaimResponse {
  kind: string
  text: string
  status: ClaimStatus
  confidence: number | null
  sourceNodeId: string | null
  sourceAnswerId: string | null
}

export interface AnswerPatchResponse {
  id: string
  projectId: string
  routeId: string
  sourceNodeId: string
  sourceAnswerId: string
  claims: ClaimResponse[]
  createdAt: string
}

export interface AnswerExecutionResponse {
  agentRun: AgentRunResponse
  answer: AnswerResponse
  answerPatch: AnswerPatchResponse
  nextNode: NodeResponse | null
}

export interface DraftQuestionResponse {
  agentRun: AgentRunResponse
  producedNode: NodeResponse
}

/** 回答请求:API 层两个输入都可选,但至少需要一个。 */
export interface SubmitAnswerRequest {
  selectedOptionId?: string | null
  /** 多选题的全量选择（用户顺序）；单选题不传，走 selectedOptionId。 */
  selectedOptionIds?: string[] | null
  freeText?: string | null
  /**
   * 显式回答目标（可选）：回答哪个节点、答案写入哪条路线。
   *
   * 聚焦一条**非运行**路线的末端时前端会带上它们 —— 后端据此把 run 绑定到该
   * 路线（多路线各自独立生成/回答）。缺省时仍走运行路线的当前节点（原语义）。
   */
  nodeId?: string | null
  routeId?: string | null
}

export interface RequirementClaimView {
  kind: string
  text: string
  status: ClaimStatus
  confidence: number | null
  sourceNodeId: string | null
  sourceAnswerId: string | null
}

export interface RequirementStateView {
  projectId: string
  routeId: string | null
  confirmed: RequirementClaimView[]
  assumed: RequirementClaimView[]
  unresolved: RequirementClaimView[]
  rejected: RequirementClaimView[]
  builtAt: string
}

/** 路线 mutation 命令之后的新鲜状态。 */
export interface RouteMutationResponse {
  projectId: string
  route: RouteResponse
  activeRouteId: string | null
  /** 仅 resume 命令携带。 */
  resumedNewRoute?: boolean
}

/** 显式来源的 fork 请求;运行时拥有所有生成的路线 id。 */
export interface ForkRouteRequest {
  sourceRouteId: string
  label?: string | null
}

export interface ReanswerRouteRequest {
  sourceRouteId: string
  label?: string | null
}

/** 模型驱动的换题请求;模型内容绝不在浏览器端撰写。 */
export interface RegenerateNodeRequest {
  sourceRouteId: string
  instruction?: string | null
}

/** 运行时的确定性换题结果。 */
export interface RegenerateResponse {
  projectId: string
  oldRoute: RouteResponse
  replacementRoute: RouteResponse
  replacementNode: NodeResponse
}

export interface RouteLineageOptionView {
  id: string
  label: string
  impact: string | null
}

export interface RouteLineageNodeView {
  id: string
  projectId: string
  parentNodeId: string | null
  supersedesNodeId: string | null
  question: string
  purpose: string | null
  options: RouteLineageOptionView[]
  allowFreeAnswer: boolean
  createdAt: string
}

/** 后端派生的路线谱系读取视图(根→末端顺序)。 */
export interface RouteLineageView {
  projectId: string
  routeId: string
  rootNodeId: string | null
  tipNodeId: string | null
  lifecycleStatus: RouteLifecycleStatus
  isActive: boolean
  nodes: RouteLineageNodeView[]
}

/** 派生规格快照的只读章节。 */
export interface SpecSectionResponse {
  id: string
  title: string
  content: string
}

/** 派生规格快照的只读未解决项。 */
export interface UnresolvedItemResponse {
  text: string
  category: string
}

/** 规格断言到运行时记录的只读出处指针。 */
export interface SourceReferenceResponse {
  kind: string
  refId: string
}

/** 派生的规格快照;绝不是权威来源。 */
export interface SpecSnapshotResponse {
  id: string
  projectId: string
  routeId: string
  tipNodeId: string | null
  contextSnapshotId: string | null
  format: string
  sections: SpecSectionResponse[]
  unresolvedItems: UnresolvedItemResponse[]
  sourceRefs: SourceReferenceResponse[]
  createdByRunId: string | null
  createdAt: string
}

/** 规格生成命令的结果。 */
export interface SpecGenerationResponse {
  agentRun: AgentRunResponse
  specSnapshot: SpecSnapshotResponse
}
/** canonical 项目图内的只读选项视图。 */
export interface GraphWorkspaceOptionView {
  id: string
  label: string
  impact: string | null
  /** 模型基于上下文的推荐（只是建议，绝不预选中）。 */
  recommended: boolean
}

/** 稳定的外层节点 kind;子类型做细化(没有按业务的节点类型)。 */
export type GraphNodeKind = 'KNOWLEDGE' | 'INTERACTION' | 'RESOURCE' | 'ARTIFACT'

/** canonical 项目图上的只读节点视图(已去重)。 */
export interface GraphWorkspaceNodeView {
  id: string
  projectId: string
  parentNodeId: string | null
  supersedesNodeId: string | null
  question: string
  purpose: string | null
  options: GraphWorkspaceOptionView[]
  allowFreeAnswer: boolean
  allowMultiSelect: boolean
  createdAt: string
  kind: GraphNodeKind
  subtype: string
  content: Record<string, unknown>
  authorKind: 'USER' | 'AGENT' | 'RUNTIME'
  knowledgeStatus: 'PROPOSED' | 'CONFIRMED' | 'CHALLENGED' | 'SUPERSEDED' | null
  userEditableDraft: boolean
}

/** 只读回答展示视图;身份始终是 routeId + nodeId。 */
export interface GraphWorkspaceAnswerView {
  id: string
  routeId: string
  ownerRouteId?: string
  inherited?: boolean
  nodeId: string
  selectedOptionId: string | null
  /** 多选题的全量选择（用户顺序）；单选答案为 null 或单元素。 */
  selectedOptionIds: string[] | null
  freeText: string | null
  createdAt: string
}

/** canonical 项目图上的只读路线视图。 */
export interface GraphWorkspaceRouteView {
  id: string
  label: string | null
  lifecycleStatus: RouteLifecycleStatus
  isActive: boolean
  rootNodeId: string | null
  tipNodeId: string | null
  createdFromNodeId: string | null
  supersedesRouteId: string | null
  replacementOfNodeId: string | null
  branchType?: 'fork' | 'reanswer' | 'regenerate' | 'continuation' | null
  sourceRouteId?: string | null
  branchAtNodeId?: string | null
  lineageNodeIds: string[]
}

/** 活跃的语义关系(Inspector 数据;绝不是默认画布边)。 */
export interface GraphWorkspaceRelationView {
  id: string
  sourceNodeId: string
  targetNodeId: string
  relationType: 'RELATED_TO' | 'DEPENDS_ON' | 'DERIVED_FROM' | 'CONFLICTS_WITH' | 'SUPPORTS'
  origin: 'USER' | 'AGENT' | 'RUNTIME'
  createdByProposalId: string | null
  createdAt: string
}

/** 工作区使用的 canonical 只读项目图。 */
export interface GraphWorkspaceView {
  projectId: string
  activeRouteId: string | null
  routes: GraphWorkspaceRouteView[]
  nodes: GraphWorkspaceNodeView[]
  answers: GraphWorkspaceAnswerView[]
  relations: GraphWorkspaceRelationView[]
}
