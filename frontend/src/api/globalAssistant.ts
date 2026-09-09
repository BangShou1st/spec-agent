import { apiClient } from './client'

/** Global Assistant V1 public contracts (frozen backend). */
export type GaCurrentPage = 'PROJECTS' | 'PROJECT' | 'SKILLS' | 'CONNECTIONS' | 'SETTINGS' | 'UNKNOWN'

export interface GaSelectedEntity {
  type: string
  id: string
}

export interface GaUiContext {
  currentPage: GaCurrentPage
  selectedEntity: GaSelectedEntity | null
}

export interface GaThreadResponse {
  threadId: string
  summary: string
  summaryVersion: number
  workingStateVersion: number
  createdAt: string
  updatedAt: string
}

export type GaMessageRole = 'USER' | 'ASSISTANT'

export interface GaMessage {
  id: string
  threadId: string
  role: GaMessageRole
  content: string
  runId: string | null
  createdAt: string
}

export type GaRunStatus = 'CREATED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

export interface GaRun {
  runId: string
  threadId: string
  status: GaRunStatus
  stepCount: number
  cancelRequestedAt: string | null
  startedAt: string
  completedAt: string | null
  errorCode: string | null
}

export type GaEventType =
  | 'RUN_STARTED'
  | 'STATUS'
  | 'ASSISTANT_DELTA'
  | 'TOOL_STARTED'
  | 'TOOL_COMPLETED'
  | 'TOOL_FAILED'
  | 'USER_INPUT_REQUIRED'
  | 'APPROVAL_REQUIRED'
  | 'UI_ACTION'
  | 'ASSISTANT_COMPLETED'
  | 'RUN_COMPLETED'
  | 'RUN_FAILED'
  | 'RUN_CANCELLED'
  | (string & {})

export interface GaEventEnvelope<TPayload = Record<string, unknown>> {
  eventId: string
  runId: string
  threadId: string
  type: GaEventType
  sequence: number
  createdAt: string
  payload: TPayload
}

export const GA_MAX_MESSAGE_LENGTH = 4000
export const GA_COMPOSER_WARN_AT = 3500

const BASE = '/global-assistant'

export function createGaThread(): Promise<{ threadId: string }> {
  return apiClient.post<{ threadId: string }>(BASE + '/threads')
}

export function createGaRun(
  threadId: string,
  message: string,
  uiContext: GaUiContext,
): Promise<{ runId: string; status: GaRunStatus }> {
  return apiClient.post<{ runId: string; status: GaRunStatus }>(
    BASE + '/threads/' + threadId + '/runs',
    { message, uiContext },
  )
}

export function getGaThread(threadId: string): Promise<GaThreadResponse> {
  return apiClient.get<GaThreadResponse>(BASE + '/threads/' + threadId)
}

export function listGaMessages(threadId: string): Promise<GaMessage[]> {
  return apiClient.get<GaMessage[]>(BASE + '/threads/' + threadId + '/messages')
}

export function getGaRun(runId: string): Promise<GaRun> {
  return apiClient.get<GaRun>(BASE + '/runs/' + runId)
}

export function listGaEvents(runId: string): Promise<GaEventEnvelope[]> {
  return apiClient.get<GaEventEnvelope[]>(BASE + '/runs/' + runId + '/events')
}

export function cancelGaRun(runId: string): Promise<GaRun> {
  return apiClient.post<GaRun>(BASE + '/runs/' + runId + '/cancel')
}

export interface GaThreadListItem {
  threadId: string
  title: string
  preview: string
  updatedAt: string
  createdAt: string
}

export function listGaThreads(): Promise<GaThreadListItem[]> {
  return apiClient.get<GaThreadListItem[]>(BASE + '/threads')
}

/** Minimal route shape so the mapper stays testable without vue-router. */
export interface GaRouteLike {
  path: string
  params?: Record<string, string | string[] | undefined>
}

/** Deterministic UI context projection from Vue Router state. */
export function buildGaUiContext(route: GaRouteLike): GaUiContext {
  const path = route.path || ''
  const rawId = route.params?.projectId
  const projectId = Array.isArray(rawId) ? rawId[0] : rawId
  if (path === '/projects' || path === '/projects/') {
    return { currentPage: 'PROJECTS', selectedEntity: null }
  }
  if (path.startsWith('/projects/') && projectId) {
    return { currentPage: 'PROJECT', selectedEntity: { type: 'PROJECT', id: projectId } }
  }
  if (path.startsWith('/settings/skills')) {
    return { currentPage: 'SKILLS', selectedEntity: null }
  }
  if (path.startsWith('/settings/connections')) {
    return { currentPage: 'CONNECTIONS', selectedEntity: null }
  }
  if (path.startsWith('/settings')) {
    return { currentPage: 'SETTINGS', selectedEntity: null }
  }
  return { currentPage: 'UNKNOWN', selectedEntity: null }
}

export type GaUiDestination = 'PROJECT' | 'PROJECTS' | 'SKILLS' | 'CONNECTIONS' | 'SETTINGS'

/** Typed UI_ACTION destination mapping. Only UI_ACTION drives navigation. */
export function gaUiActionToRoute(destination: string, resourceId?: string | null): string | null {
  switch (destination) {
    case 'PROJECT':
      return resourceId ? '/projects/' + resourceId : null
    case 'PROJECTS':
      return '/projects'
    case 'SKILLS':
      return '/settings/skills'
    case 'CONNECTIONS':
      return '/settings/connections'
    case 'SETTINGS':
      return '/settings'
    default:
      return null
  }
}

export function isGaTerminalStatus(status: string): boolean {
  return status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELLED'
}

export function isGaTerminalEventType(type: string): boolean {
  return type === 'RUN_COMPLETED' || type === 'RUN_FAILED' || type === 'RUN_CANCELLED'
}
