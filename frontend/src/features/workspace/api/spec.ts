// 文件名:spec.ts
// 用途:Spec(规格)读取与生成 API。
// 前端从不编写 SpecSnapshot:生成走既有的后端命令,产出的派生制品再从后端重读。
// 快照是派生物,永远不是事实源。

import { API_BASE_URL, ApiError, GENERIC_ERROR_MESSAGE, apiClient } from '@/shared/http/client'
import type { SpecGenerationResponse, SpecSnapshotResponse } from '@/shared/contracts/types'

export function generateSpec(projectId: string): Promise<SpecGenerationResponse> {
  return apiClient.post<SpecGenerationResponse>(`/projects/${projectId}/specs/generate`)
}

export function listRouteSpecs(
  projectId: string,
  routeId: string,
): Promise<SpecSnapshotResponse[]> {
  return apiClient.get<SpecSnapshotResponse[]>(
    `/projects/${projectId}/routes/${routeId}/specs`,
  )
}

export function getSpecSnapshot(snapshotId: string): Promise<SpecSnapshotResponse> {
  return apiClient.get<SpecSnapshotResponse>(`/specs/${snapshotId}`)
}

/**
 * Spec 快照 Markdown 渲染的导出变体。后端对存储的快照做确定性渲染——
 * 不调用模型,也没有第二份副本。
 *
 * - `snapshot`:忠实、溯源完整的导出(审计视图)。
 * - `delivery`:开发交接文档(PRD 风格,溯源信息放附录)。
 */
export type SpecExportVariant = 'snapshot' | 'delivery'

/**
 * 下载一个快照的 Markdown 导出并触发浏览器保存。
 * 下载交付给浏览器后即 resolve;任何失败抛 ApiError。
 * 直接 fetch 后端(类型化客户端只解析 JSON),错误契约处理保持一致。
 */
export async function downloadSpecMarkdown(
  snapshotId: string,
  variant: SpecExportVariant,
): Promise<void> {
  let response: Response
  try {
    response = await fetch(
      `${API_BASE_URL}/specs/${snapshotId}/export.md?variant=${variant}`,
    )
  } catch {
    throw new ApiError(GENERIC_ERROR_MESSAGE, 'NETWORK_ERROR', 0)
  }
  if (!response.ok) {
    throw await toApiError(response)
  }
  const blob = await response.blob()
  const shortId = snapshotId.slice(0, 8)
  const filename =
    variant === 'delivery'
      ? `开发需求文档-${shortId}.md`
      : `需求规格快照-${shortId}.md`
  const url = URL.createObjectURL(blob)
  try {
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = filename
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
  } finally {
    URL.revokeObjectURL(url)
  }
}

async function toApiError(response: Response): Promise<ApiError> {
  try {
    const parsed: unknown = await response.json()
    if (typeof parsed === 'object' && parsed !== null) {
      const candidate = parsed as { code?: unknown; message?: unknown }
      if (typeof candidate.code === 'string' && typeof candidate.message === 'string') {
        return new ApiError(candidate.message, candidate.code, response.status)
      }
    }
  } catch {
    // 解析失败则继续走下面的通用失败分支。
  }
  return new ApiError(GENERIC_ERROR_MESSAGE, 'UNKNOWN_ERROR', response.status)
}
