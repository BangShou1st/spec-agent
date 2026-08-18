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