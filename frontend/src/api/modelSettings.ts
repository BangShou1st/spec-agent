import { apiClient } from './client'
import type { OpenCodeProbeResponse, OpenCodeSettingsStatus } from './types'

export function getOpenCodeSettings(): Promise<OpenCodeSettingsStatus> {
  return apiClient.get<OpenCodeSettingsStatus>('/settings/opencode')
}

export function probeOpenCode(apiKey: string): Promise<OpenCodeProbeResponse> {
  return apiClient.post<OpenCodeProbeResponse>('/settings/opencode/probe', { apiKey })
}

export function saveOpenCode(apiKey: string, selectedModel: string): Promise<OpenCodeSettingsStatus> {
  return apiClient.put<OpenCodeSettingsStatus>('/settings/opencode', { apiKey, selectedModel })
}
