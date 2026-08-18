import type {
  ActiveProjectStateResponse,
  AgentRunResponse,
  AnswerExecutionResponse,
  AnswerPatchResponse,
  AnswerResponse,
  NodeResponse,
  ProjectResponse,
  ProjectSummaryResponse,
  GraphWorkspaceView,
  RegenerateResponse,
  RequirementClaimView,
  RequirementStateView,
  RouteLineageNodeView,
  RouteLineageView,
  RouteMutationResponse,
  RouteResponse,
  SourceReferenceResponse,
  SpecGenerationResponse,
  SpecSectionResponse,
  SpecSnapshotResponse,
  UnresolvedItemResponse,
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

export function makeAgentRun(overrides: Partial<AgentRunResponse> = {}): AgentRunResponse {
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
    ...overrides,
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

export function makeRouteMutation(
  overrides: Partial<RouteMutationResponse> = {},
): RouteMutationResponse {
  return {
    projectId: 'project-1',
    route: makeRoute({ isActive: true }),
    activeRouteId: 'route-1',
    ...overrides,
  }
}

export function makeRouteLineageNode(
  overrides: Partial<RouteLineageNodeView> = {},
): RouteLineageNodeView {
  return {
    id: nextId('lnode'),
    projectId: 'project-1',
    parentNodeId: null,
    supersedesNodeId: null,
    question: 'Historical question',
    purpose: null,
    options: [],
    allowFreeAnswer: true,
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

export function makeRouteLineage(
  overrides: Partial<RouteLineageView> = {},
): RouteLineageView {
  return {
    projectId: 'project-1',
    routeId: 'route-1',
    rootNodeId: 'lnode-1',
    tipNodeId: 'lnode-2',
    lifecycleStatus: 'open',
    isActive: true,
    nodes: [
      makeRouteLineageNode({ id: 'lnode-1', question: 'Root question' }),
      makeRouteLineageNode({
        id: 'lnode-2',
        parentNodeId: 'lnode-1',
        question: 'Child question',
        purpose: 'Child purpose',
        options: [
          { id: 'opt-l1', label: 'Small scope', impact: 'Reduces scope' },
          { id: 'opt-l2', label: 'Large scope', impact: null },
        ],
      }),
    ],
    ...overrides,
  }
}

export function makeRegenerateResponse(
  overrides: Partial<RegenerateResponse> = {},
): RegenerateResponse {
  return {
    projectId: 'project-1',
    oldRoute: makeRoute({ id: 'route-old', lifecycleStatus: 'superseded', isActive: false }),
    replacementRoute: makeRoute({
      id: 'route-replacement',
      lifecycleStatus: 'open',
      isActive: true,
      label: 'Regenerated route',
    }),
    replacementNode: makeNode({ id: 'node-replacement', question: 'Replacement question' }),
    ...overrides,
  }
}

export function makeSpecSection(
  overrides: Partial<SpecSectionResponse> = {},
): SpecSectionResponse {
  return {
    id: 's1',
    title: 'Overview',
    content: 'A section of the derived spec.',
    ...overrides,
  }
}

export function makeUnresolvedItem(
  overrides: Partial<UnresolvedItemResponse> = {},
): UnresolvedItemResponse {
  return {
    text: 'An unresolved aspect.',
    category: 'unresolved',
    ...overrides,
  }
}

export function makeSourceReference(
  overrides: Partial<SourceReferenceResponse> = {},
): SourceReferenceResponse {
  return {
    kind: 'node',
    refId: 'lnode-1',
    ...overrides,
  }
}

export function makeSpecSnapshot(
  overrides: Partial<SpecSnapshotResponse> = {},
): SpecSnapshotResponse {
  return {
    id: nextId('spec'),
    projectId: 'project-1',
    routeId: 'route-1',
    tipNodeId: 'lnode-2',
    contextSnapshotId: 'context-1',
    format: 'markdown',
    sections: [makeSpecSection()],
    unresolvedItems: [makeUnresolvedItem()],
    sourceRefs: [makeSourceReference()],
    createdByRunId: 'run-1',
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

export function makeGraphWorkspaceView(
  overrides: Partial<GraphWorkspaceView> = {},
): GraphWorkspaceView {
  return {
    projectId: 'project-1',
    activeRouteId: 'route-1',
    routes: [],
    nodes: [],
    answers: [],
    ...overrides,
  }
}

export function makeSpecGeneration(
  overrides: Partial<SpecGenerationResponse> = {},
): SpecGenerationResponse {
  return {
    agentRun: makeAgentRun({ triggerType: 'generate_spec' }),
    specSnapshot: makeSpecSnapshot({ id: 'spec-new' }),
    ...overrides,
  }
}