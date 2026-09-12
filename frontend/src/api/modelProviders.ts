import { apiClient } from './client'

export type ModelProvider = 'OPENCODE_ZEN' | 'OPENROUTER' | 'CUSTOM'
export type CustomApiFormat = 'CHAT_COMPLETIONS' | 'RESPONSES' | 'ANTHROPIC_MESSAGES'

export interface ActiveProviderResponse {
  activeProvider: ModelProvider
}

export interface OpenRouterStatus {
  configured: boolean
  maskedKey: string | null
  selectedModel: string | null
  configRevision: number
  validated: boolean
  active: boolean
}

export interface CustomStatus {
  configured: boolean
  apiFormat: CustomApiFormat
  baseUrl: string
  endpointPreview: string | null
  hasKey: boolean
  maskedKey: string | null
  selectedModel: string
  manualModel: boolean
  configRevision: number
  validated: boolean
}

export function getActiveProvider(): Promise<ActiveProviderResponse> {
  return apiClient.get<ActiveProviderResponse>('/settings/providers/active')
}

export function activateProvider(provider: ModelProvider): Promise<ActiveProviderResponse> {
  return apiClient.post<ActiveProviderResponse>('/settings/providers/activate', { provider })
}

export function getOpenRouterStatus(): Promise<OpenRouterStatus> {
  return apiClient.get<OpenRouterStatus>('/settings/openrouter')
}

export function probeOpenRouter(apiKey: string): Promise<{ freeModels: string[] }> {
  return apiClient.post<{ freeModels: string[] }>('/settings/openrouter/probe', { apiKey })
}

export function listOpenRouterModels(): Promise<{ freeModels: string[] }> {
  return apiClient.get<{ freeModels: string[] }>('/settings/openrouter/models')
}

export function saveOpenRouter(apiKey: string | null, selectedModel: string): Promise<OpenRouterStatus> {
  return apiClient.put<OpenRouterStatus>('/settings/openrouter', { apiKey, selectedModel })
}

export function validateOpenRouter(): Promise<OpenRouterStatus> {
  return apiClient.post<OpenRouterStatus>('/settings/openrouter/validate')
}

export function getCustomStatus(): Promise<CustomStatus> {
  return apiClient.get<CustomStatus>('/settings/custom')
}

export function discoverCustom(apiFormat: CustomApiFormat, baseUrl: string, apiKey?: string | null): Promise<{ models: string[]; manualModel: boolean; endpointPreview: string | null }> {
  return apiClient.post<{ models: string[]; manualModel: boolean; endpointPreview: string | null }>('/settings/custom/discover', { apiFormat, baseUrl, apiKey })
}

export function saveCustom(apiFormat: CustomApiFormat, baseUrl: string, apiKey: string | null | undefined, selectedModel: string): Promise<CustomStatus> {
  return saveCustomWithSource(apiFormat, baseUrl, apiKey, selectedModel, undefined)
}

export function saveCustomWithSource(apiFormat: CustomApiFormat, baseUrl: string, apiKey: string | null | undefined, selectedModel: string, modelSource: 'DISCOVERED' | 'MANUAL' | undefined): Promise<CustomStatus> {
  // undefined = retain stored key; null/empty = clear; non-empty = new key.
  // JSON omits undefined, so retain semantics survive the wire.
  // modelSource persists manual-model mode across reloads.
  return apiClient.put<CustomStatus>('/settings/custom', { apiFormat, baseUrl, apiKey, selectedModel, modelSource })
}

export function validateCustom(): Promise<CustomStatus> {
  return apiClient.post<CustomStatus>('/settings/custom/validate')
}
