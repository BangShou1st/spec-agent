// 文件名:workspace.ts
// 用途:工作台读取 API。回答/修复/问题草稿等变更都在异步 AgentRun 层(api/agentRuns.ts);
// 本模块只暴露规范读取。

import { apiClient } from '@/shared/http/client'
import type {
  ActiveProjectStateResponse,
  RouteResponse,
} from '@/shared/contracts/types'

/**
 * 工作台读取 API。回答/修复/问题草稿等变更属于异步 AgentRun 层(api/agentRuns.ts);
 * 本模块只暴露规范读取。
 */
export function getActiveState(projectId: string): Promise<ActiveProjectStateResponse> {
  return apiClient.get<ActiveProjectStateResponse>(`/projects/${projectId}/active`)
}

export function listRoutes(projectId: string): Promise<RouteResponse[]> {
  return apiClient.get<RouteResponse[]>(`/projects/${projectId}/routes`)
}
