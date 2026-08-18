import { apiClient } from './client'
import type { CreateProjectRequest, ProjectResponse, ProjectSummaryResponse } from './types'

export function listProjects(): Promise<ProjectSummaryResponse[]> {
  return apiClient.get<ProjectSummaryResponse[]>('/projects')
}

export function createProject(title: string): Promise<ProjectResponse> {
  const body: CreateProjectRequest = { title }
  return apiClient.post<ProjectResponse>('/projects', body)
}

export function getProject(projectId: string): Promise<ProjectResponse> {
  return apiClient.get<ProjectResponse>(`/projects/${projectId}`)
}