// 文件名:modelSettings.ts
// 用途:OpenCode Zen 模型设置的 API 封装:读取设置、探测密钥、获取模型列表、
//       保存密钥与模型、单独切换模型,以及校验连通性。

import { apiClient } from '@/shared/http/client'
import type { OpenCodeProbeResponse, OpenCodeSettingsStatus } from '@/shared/contracts/types'

export function getOpenCodeSettings(): Promise<OpenCodeSettingsStatus> {
  return apiClient.get<OpenCodeSettingsStatus>('/settings/opencode')
}

export function probeOpenCode(apiKey: string): Promise<OpenCodeProbeResponse> {
  return apiClient.post<OpenCodeProbeResponse>('/settings/opencode/probe', { apiKey })
}

export function listOpenCodeModels(): Promise<OpenCodeProbeResponse> {
  return apiClient.get<OpenCodeProbeResponse>('/settings/opencode/models')
}

export function saveOpenCode(apiKey: string, selectedModel: string): Promise<OpenCodeSettingsStatus> {
  return apiClient.put<OpenCodeSettingsStatus>('/settings/opencode', { apiKey, selectedModel })
}

export function saveOpenCodeModel(selectedModel: string): Promise<OpenCodeSettingsStatus> {
  return apiClient.put<OpenCodeSettingsStatus>('/settings/opencode/model', { selectedModel })
}

/** 用已存的密钥 + 模型做显式连通性校验;绝不修改设置。 */
export function validateOpenCode(): Promise<OpenCodeSettingsStatus> {
  return apiClient.post<OpenCodeSettingsStatus>('/settings/opencode/validate')
}
