// 文件名:modelProviders.ts
// 用途:模型设置(Provider)的 API 封装与类型:激活的 Provider 查询/切换,
//       其他 Provider 的状态/探测/模型列表/保存/校验,以及自定义 Provider 的
//       状态/发现/保存/校验端点。

import { apiClient } from '@/shared/http/client'

export type ModelProvider = 'OPENCODE_ZEN' | 'KILO' | 'CUSTOM'
export type CustomApiFormat = 'CHAT_COMPLETIONS' | 'RESPONSES' | 'ANTHROPIC_MESSAGES'

export interface ActiveProviderResponse {
  activeProvider: ModelProvider
  activeProviderId?: string | null
}

export interface ProviderConnection extends CustomStatus {
  id: string
  preset: ModelProvider
  active: boolean
  verifiedCapabilities: Record<'CHAT' | 'STRUCTURED' | 'NATIVE_TOOLS', string>
  capabilities: { userNamed: boolean; selectableFormat: boolean; editableBaseUrl: boolean; requiresApiKey: boolean; builtInCatalog: boolean }
}
export interface ProviderConnections {
  providers: ProviderConnection[]
  activeProvider: ModelProvider
  activeProviderId: string | null
}
export interface ConnectionPatch {
  displayName: string
  apiFormat: CustomApiFormat
  baseUrl: string
  apiKey?: string
  selectedModel: string
  modelSource: 'MANUAL' | 'DISCOVERED'
}
export interface ConnectionModels {
  models: string[]
  freeModels: string[]
  manualModel: boolean
  endpointPreview: string | null
}
export const listConnections = () => apiClient.get<ProviderConnections>('/settings/providers')
export const saveConnection = (id: string | null, patch: ConnectionPatch) => id
  ? apiClient.put<ProviderConnection>(`/settings/providers/${id}`, patch)
  : apiClient.post<ProviderConnection>('/settings/providers', patch)
export const deleteConnection = (id: string) => apiClient.delete<void>(`/settings/providers/${id}`)
export const validateConnection = (id: string) => apiClient.post<ProviderConnection>(`/settings/providers/${id}/validate`)
export const activateConnection = (id: string) => apiClient.post<ProviderConnections>(`/settings/providers/${id}/activate`)
export const discoverConnection = (id: string | null, patch: Pick<ConnectionPatch, 'apiFormat' | 'baseUrl' | 'apiKey'>) =>
  apiClient.post<ConnectionModels>(id ? `/settings/providers/${id}/probe` : '/settings/providers/discover', patch)

export interface CustomStatus {
  configured: boolean
  apiFormat: CustomApiFormat
  baseUrl: string
  endpointPreview: string | null
  hasKey: boolean
  maskedKey: string | null
  selectedModel: string
  manualModel: boolean
  /** 面向用户的状态胶囊标签;后端对旧数据行回退为 'Custom'。 */
  displayName: string | null
  configRevision: number
  validated: boolean
}

export function getActiveProvider(): Promise<ActiveProviderResponse> {
  return apiClient.get<ActiveProviderResponse>('/settings/providers/active')
}

export function activateProvider(provider: ModelProvider): Promise<ActiveProviderResponse> {
  return apiClient.post<ActiveProviderResponse>('/settings/providers/activate', { provider })
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

export function saveCustomWithSource(apiFormat: CustomApiFormat, baseUrl: string, apiKey: string | null | undefined, selectedModel: string, modelSource: 'DISCOVERED' | 'MANUAL' | undefined, displayName?: string | null): Promise<CustomStatus> {
  // undefined = 保留已存密钥;null/空串 = 清除;非空 = 新密钥。
  // JSON 序列化会省略 undefined 字段,因此"保留"语义能在线上存活。
  // modelSource 用于跨刷新保留手动填写模型模式。
  // displayName 命名 Provider 状态胶囊;undefined 表示保留已存名称。
  return apiClient.put<CustomStatus>('/settings/custom', { apiFormat, baseUrl, apiKey, selectedModel, modelSource, displayName })
}

export function validateCustom(): Promise<CustomStatus> {
  return apiClient.post<CustomStatus>('/settings/custom/validate')
}
