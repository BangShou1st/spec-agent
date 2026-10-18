// 文件名:kiloSettings.ts
// 用途:Kilo Gateway 模型设置的 API 封装:读取设置、探测密钥、获取模型列表、
//       保存密钥与模型、单独切换模型,以及校验连通性。

import { apiClient } from '@/shared/http/client'
import type { KiloProbeResponse, KiloSettingsStatus } from '@/shared/contracts/types'

export function getKiloSettings(): Promise<KiloSettingsStatus> {
  return apiClient.get<KiloSettingsStatus>('/settings/kilo')
}

export function probeKilo(apiKey: string): Promise<KiloProbeResponse> {
  return apiClient.post<KiloProbeResponse>('/settings/kilo/probe', { apiKey })
}

export function listKiloModels(): Promise<KiloProbeResponse> {
  return apiClient.get<KiloProbeResponse>('/settings/kilo/models')
}

export function saveKilo(apiKey: string | null, selectedModel: string): Promise<KiloSettingsStatus> {
  return apiClient.put<KiloSettingsStatus>('/settings/kilo', { apiKey, selectedModel })
}

export function saveKiloModel(selectedModel: string): Promise<KiloSettingsStatus> {
  return apiClient.put<KiloSettingsStatus>('/settings/kilo/model', { selectedModel })
}

/** 用已存的密钥 + 模型做显式连通性校验;绝不修改设置。 */
export function validateKilo(): Promise<KiloSettingsStatus> {
  return apiClient.post<KiloSettingsStatus>('/settings/kilo/validate')
}
