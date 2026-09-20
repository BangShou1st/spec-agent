import { apiClient } from './client'
import type { CreateProjectRequest, ProjectResponse, ProjectSummaryResponse } from './types'

export function listProjects(title?: string): Promise<ProjectSummaryResponse[]> {
  const path = title ? `/projects?title=${encodeURIComponent(title)}` : '/projects'
  return apiClient.get<ProjectSummaryResponse[]>(path)
}

export function createProject(title: string): Promise<ProjectResponse> {
  const body: CreateProjectRequest = { title }
  return apiClient.post<ProjectResponse>('/projects', body)
}

export function getProject(projectId: string): Promise<ProjectResponse> {
  return apiClient.get<ProjectResponse>(`/projects/${projectId}`)
}

export function renameProject(projectId: string, title: string): Promise<ProjectResponse> {
  return apiClient.put<ProjectResponse>(`/projects/${projectId}/title`, { title })
}

export function deleteProject(projectId: string): Promise<void> {
  return apiClient.delete<void>(`/projects/${projectId}`)
}
