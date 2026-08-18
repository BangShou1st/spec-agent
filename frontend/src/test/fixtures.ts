import type {
  ActiveProjectStateResponse,
  AgentRunResponse,
  AnswerExecutionResponse,
  AnswerPatchResponse,
  AnswerResponse,
  NodeResponse,
  ProjectResponse,
  ProjectSummaryResponse,
  RequirementClaimView,
  RequirementStateView,
  RouteResponse,
} from '@/api/types'

/** Test fixtures mirroring the backend API contracts. Ids are opaque strings. */

let sequence = 0
function nextId(prefix: string): string {
  sequence += 1
  return `${prefix}-${sequence}`
}

export function makeProjectSummary(overrides: Partial<ProjectSummaryResponse> = {}): ProjectSummaryResponse {
  return {
    id: nextId('project'),
    title: 'Test project',
    activeRouteId: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

export function makeProject(overrides: Partial<ProjectResponse> = {}): ProjectResponse {
  return {
    id: nextId('project'),
    title: 'Test project',
    activeRouteId: null,
    defaultProfileId: 'profile-1',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

export function makeRoute(overrides: Partial<RouteResponse> = {}): RouteResponse {
  return {
    id: nextId('route'),
    projectId: 'project-1',
    rootNodeId: null,
    tipNodeId: null,
    lifecycleStatus: 'open',
    label: 'Initial route',
    createdFromNodeId: null,
    supersedesRouteId: null,
    replacementOfNodeId: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    isActive: false,
    ...overrides,
  }
}

export function makeNode(overrides: Partial<NodeResponse> = {}): NodeResponse {
  return {
    id: nextId('node'),
    projectId: 'project-1',
    parentNodeId: null,
    supersedesNodeId: null,
    question: 'What is the most important outcome?',
    purpose: 'Clarifies the primary requirement goal.',
    options: [],
    allowFreeAnswer: true,
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

export function makeActiveState(
  overrides: Partial<ActiveProjectStateResponse> = {},
): ActiveProjectStateResponse {
  return {
    project: makeProject(),
    activeRoute: makeRoute({ isActive: true }),
    activeNode: makeNode(),
    ...overrides,
  }
}

export function makeClaim(overrides: Partial<RequirementClaimView> = {}): RequirementClaimView {
  return {
    kind: 'goal',
    text: 'A confirmed requirement detail.',
    status: 'confirmed',
    confidence: 0.9,
    sourceNodeId: 'node-1',
    sourceAnswerId: 'answer-1',
    ...overrides,
  }
}

export function makeRequirementState(
  overrides: Partial<RequirementStateView> = {},
): RequirementStateView {
  return {
    projectId: 'project-1',
    routeId: 'route-1',
    confirmed: [makeClaim()],
    assumed: [],
    unresolved: [],
    rejected: [],
    builtAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

export function makeAgentRun(): AgentRunResponse {
  return {
    id: nextId('run'),
    projectId: 'project-1',
    routeId: 'route-1',
    triggerType: 'draft_node',
    inputNodeId: null,
    contextSnapshotId: 'snapshot-1',
    producedNodeId: 'node-1',
    producedAnswerId: null,
    producedPatchId: null,
    producedSpecSnapshotId: null,
    status: 'completed',
    traceSteps: ['created', 'context_built', 'completed'],
    createdAt: '2026-01-01T00:00:00Z',
    completedAt: '2026-01-01T00:00:00Z',
  }
}

export function makeAnswer(): AnswerResponse {
  return {
    id: nextId('answer'),
    projectId: 'project-1',
    routeId: 'route-1',
    nodeId: 'node-1',
    selectedOptionId: null,
    freeText: 'Free text answer',
    createdAt: '2026-01-01T00:00:00Z',
  }
}

export function makeAnswerPatch(): AnswerPatchResponse {
  return {
    id: nextId('patch'),
    projectId: 'project-1',
    routeId: 'route-1',
    sourceNodeId: 'node-1',
    sourceAnswerId: 'answer-1',
    claims: [makeClaim()],
    createdAt: '2026-01-01T00:00:00Z',
  }
}

export function makeAnswerExecution(
  overrides: Partial<AnswerExecutionResponse> = {},
): AnswerExecutionResponse {
  return {
    agentRun: makeAgentRun(),
    answer: makeAnswer(),
    answerPatch: makeAnswerPatch(),
    nextNode: makeNode({ question: 'Next question' }),
    ...overrides,
  }
}