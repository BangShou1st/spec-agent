import { apiClient } from './client'
import type { ConnectionDetail, ConnectionDiscovery, ConnectionPromptView, ConnectionResourceContent, ConnectionResourceView, ConnectionSummary, ConnectionToolView, CreateConnectionRequest, UpdateConnectionRequest } from './connectionTypes'

/**
 * Connection management API wrappers. Backend ConnectionController is authority.
 * Every endpoint uses product-level connectionId. No provider keywords here.
 */

export function listConnections(): Promise<ConnectionSummary[]> {
  return apiClient.get<ConnectionSummary[]>('/connections')
}

export function getConnection(connectionId: string): Promise<ConnectionDetail> {
  return apiClient.get<ConnectionDetail>(`/connections/${encodeURIComponent(connectionId)}`)
}

export function createConnection(request: CreateConnectionRequest): Promise<ConnectionSummary> {
  return apiClient.post<ConnectionSummary>('/connections', request)
}

export function updateConnection(connectionId: string, patch: UpdateConnectionRequest): Promise<ConnectionDetail> {
  return apiClient.patch<ConnectionDetail>(`/connections/${encodeURIComponent(connectionId)}`, patch)
}

export function testConnection(connectionId: string): Promise<ConnectionDiscovery> {
  return apiClient.post<ConnectionDiscovery>(`/connections/${encodeURIComponent(connectionId)}/test`)
}

export function connectConnection(connectionId: string): Promise<ConnectionDiscovery> {
  return apiClient.post<ConnectionDiscovery>(`/connections/${encodeURIComponent(connectionId)}/connect`)
}

export function refreshConnection(connectionId: string): Promise<ConnectionDiscovery> {
  return apiClient.post<ConnectionDiscovery>(`/connections/${encodeURIComponent(connectionId)}/refresh`)
}

export function enableConnection(connectionId: string): Promise<void> {
  return apiClient.post<void>(`/connections/${encodeURIComponent(connectionId)}/enable`)
}

export function disableConnection(connectionId: string): Promise<void> {
  return apiClient.post<void>(`/connections/${encodeURIComponent(connectionId)}/disable`)
}

export function deleteConnection(connectionId: string): Promise<void> {
  return apiClient.delete<void>(`/connections/${encodeURIComponent(connectionId)}`)
}

export function listConnectionTools(connectionId: string): Promise<ConnectionToolView[]> {
  return apiClient.get<ConnectionToolView[]>(`/connections/${encodeURIComponent(connectionId)}/tools`)
}

export function listConnectionResources(connectionId: string): Promise<ConnectionResourceView[]> {
  return apiClient.get<ConnectionResourceView[]>(`/connections/${encodeURIComponent(connectionId)}/resources`)
}

export function listConnectionPrompts(connectionId: string): Promise<ConnectionPromptView[]> {
  return apiClient.get<ConnectionPromptView[]>(`/connections/${encodeURIComponent(connectionId)}/prompts`)
}

export function readConnectionResource(connectionId: string, uri: string): Promise<ConnectionResourceContent> {
  return apiClient.get<ConnectionResourceContent>(`/connections/${encodeURIComponent(connectionId)}/resources/read?uri=${encodeURIComponent(uri)}`)
}
