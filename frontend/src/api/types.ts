/**
 * TypeScript contracts for the frozen Phase 6 backend API plus the one
 * Phase 7.1 UI-support read endpoint. Types mirror the real backend DTO
 * response fields; nothing is inferred from a schema generator.
 */

/** Route lifecycle only. `active` is NOT a lifecycle status. */
export type RouteLifecycleStatus = 'open' | 'superseded' | 'archived' | 'deleted'

/** Backend claim statuses; status is never inferred in the frontend. */
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
  /** Backend-derived: routeId === Project.activeRouteId at read time. */
  isActive: boolean
}

export interface NodeOptionResponse {
  id: string
  label: string
  impact: string | null
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

/** Answer request: both inputs optional at the API, at least one required. */
export interface SubmitAnswerRequest {
  selectedOptionId?: string | null
  freeText?: string | null
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

/** Fresh state after a route mutation command. */
export interface RouteMutationResponse {
  projectId: string
  route: RouteResponse
  activeRouteId: string | null
}

/** Explicit-source Fork request; runtime owns every generated route id. */
export interface ForkRouteRequest {
  sourceRouteId: string
  label?: string | null
}

export interface ReanswerRouteRequest {
  sourceRouteId: string
  label?: string | null
}

/** Model-powered replacement request; model content is never browser-authored. */
export interface RegenerateNodeRequest {
  sourceRouteId: string
  instruction?: string | null
}

/** Deterministic regenerate result from the runtime. */
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

/** Backend-derived route lineage read view (root→tip order). */
export interface RouteLineageView {
  projectId: string
  routeId: string
  rootNodeId: string | null
  tipNodeId: string | null
  lifecycleStatus: RouteLifecycleStatus
  isActive: boolean
  nodes: RouteLineageNodeView[]
}

/** Read-only section of a derived spec snapshot. */
export interface SpecSectionResponse {
  id: string
  title: string
  content: string
}

/** Read-only unresolved item of a derived spec snapshot. */
export interface UnresolvedItemResponse {
  text: string
  category: string
}

/** Read-only provenance pointer from a spec claim to a runtime record. */
export interface SourceReferenceResponse {
  kind: string
  refId: string
}

/** Derived spec snapshot; never source of truth. */
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

/** Result of a spec generation command. */
export interface SpecGenerationResponse {
  agentRun: AgentRunResponse
  specSnapshot: SpecSnapshotResponse
}
/** Read-only option view inside the canonical project graph. */
export interface GraphWorkspaceOptionView {
  id: string
  label: string
  impact: string | null
}

/** Stable outer node kind; subtypes refine it (no per-business node types). */
export type GraphNodeKind = 'KNOWLEDGE' | 'INTERACTION' | 'RESOURCE' | 'ARTIFACT'

/** Read-only node view on the canonical project graph (deduplicated). */
export interface GraphWorkspaceNodeView {
  id: string
  projectId: string
  parentNodeId: string | null
  supersedesNodeId: string | null
  question: string
  purpose: string | null
  options: GraphWorkspaceOptionView[]
  allowFreeAnswer: boolean
  createdAt: string
  kind: GraphNodeKind
  subtype: string
  content: Record<string, unknown>
  authorKind: 'USER' | 'AGENT' | 'RUNTIME'
  knowledgeStatus: 'PROPOSED' | 'CONFIRMED' | 'CHALLENGED' | 'SUPERSEDED' | null
  userEditableDraft: boolean
}

/** Read-only answer presentation view; identity stays routeId + nodeId. */
export interface GraphWorkspaceAnswerView {
  id: string
  routeId: string
  ownerRouteId?: string
  inherited?: boolean
  nodeId: string
  selectedOptionId: string | null
  freeText: string | null
  createdAt: string
}

/** Read-only route view on the canonical project graph. */
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

/** Active semantic relation (Inspector data; never a default Canvas edge). */
export interface GraphWorkspaceRelationView {
  id: string
  sourceNodeId: string
  targetNodeId: string
  relationType: 'RELATED_TO' | 'DEPENDS_ON' | 'DERIVED_FROM' | 'CONFLICTS_WITH' | 'SUPPORTS'
  origin: 'USER' | 'AGENT' | 'RUNTIME'
  createdByProposalId: string | null
  createdAt: string
}

/** Canonical read-only project graph for the workspace. */
export interface GraphWorkspaceView {
  projectId: string
  activeRouteId: string | null
  routes: GraphWorkspaceRouteView[]
  nodes: GraphWorkspaceNodeView[]
  answers: GraphWorkspaceAnswerView[]
  relations: GraphWorkspaceRelationView[]
}
