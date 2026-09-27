// 文件名:globalAssistant.ts
// 用途:全局助手(Global Assistant)的 API 封装与类型契约:线程/消息/运行/事件的 CRUD,
//       转向(steer)、取消、停止、删除等操作,以及路由状态到 UI 上下文、UI 动作到路由的映射。

import { apiClient } from '@/shared/http/client'

/** 全局助手 V1 的公开契约(后端已冻结)。 */
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
  /** 模型用量归属信息;只有 assistant 消息会携带。 */
  providerLabel?: string | null
  modelId?: string | null
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
  | 'ANSWER_STREAM_STARTED'
  | 'ANSWER_DELTA'
  | 'ANSWER_STREAM_RESET'
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

export interface GaSteerResult {
  steerId: string
  status: 'QUEUED' | 'STARTED'
  interruptedRunId: string
  successorRunId: string | null
}

export function steerGaRun(runId: string, message: string, uiContext: GaUiContext): Promise<GaSteerResult> {
  return apiClient.post<GaSteerResult>(BASE + '/runs/' + runId + '/steer', { message, uiContext })
}

export interface GaActivityRun {
  runId: string
  status: GaRunStatus
}

export interface GaActivitySteer {
  steerId: string
  message: string
  status: 'PENDING' | 'CLAIMED' | 'CONSUMED' | 'DISCARDED'
  interruptedRunId: string
  successorRunId: string | null
  createdAt: string
}

export interface GaThreadActivity {
  activeRun: GaActivityRun | null
  pendingSteer: GaActivitySteer | null
}

export function getGaThreadActivity(threadId: string): Promise<GaThreadActivity> {
  return apiClient.get<GaThreadActivity>(BASE + '/threads/' + threadId + '/activity')
}

export function stopGaThread(threadId: string): Promise<GaThreadActivity> {
  return apiClient.post<GaThreadActivity>(BASE + '/threads/' + threadId + '/stop')
}

export function deleteGaThread(threadId: string): Promise<void> {
  return apiClient.delete<void>(BASE + '/threads/' + threadId)
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

/** 最小化的路由形状,让映射函数不依赖 vue-router 也能测试。 */
export interface GaRouteLike {
  path: string
  params?: Record<string, string | string[] | undefined>
}

/** 从 Vue Router 状态确定性地投影出 UI 上下文。 */
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

/** 类型化 UI_ACTION 目标映射。只有 UI_ACTION 事件会驱动页面导航。 */
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
