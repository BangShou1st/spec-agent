import { apiClient } from './client'
import type {
  ActiveProjectStateResponse,
  AnswerExecutionResponse,
  DraftQuestionResponse,
  RouteResponse,
  SubmitAnswerRequest,
} from './types'

export function getActiveState(projectId: string): Promise<ActiveProjectStateResponse> {
  return apiClient.get<ActiveProjectStateResponse>(`/projects/${projectId}/active`)
}

export function listRoutes(projectId: string): Promise<RouteResponse[]> {
  return apiClient.get<RouteResponse[]>(`/projects/${projectId}/routes`)
}

/** Explicit user action; never called automatically when a page opens. */
export function draftNextQuestion(projectId: string): Promise<DraftQuestionResponse> {
  return apiClient.post<DraftQuestionResponse>(`/projects/${projectId}/questions/next`)
}

export function submitAnswer(
  projectId: string,
  payload: SubmitAnswerRequest,
): Promise<AnswerExecutionResponse> {
  return apiClient.post<AnswerExecutionResponse>(`/projects/${projectId}/answers`, payload)
}

/** Explicit user repair of an already persisted answer; never a second answer submission. */
export function repairAnswer(projectId: string, answerId: string): Promise<AnswerExecutionResponse> {
  return apiClient.post<AnswerExecutionResponse>(`/projects/${projectId}/answers/${answerId}/repair`)
}
