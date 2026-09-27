// 文件名:routes.ts
// 用途:路线命令与谱系读取 API。
// 命令走既有的后端路线命令端点;前端从不在本地复现路线状态转移。
// Fork/重新生成发送显式的 sourceRouteId 加用户控制的内容;
// 运行时生成的 id(routeId、rootNodeId、tipNodeId、选项 id、生命周期状态)绝不出现在请求里。

import { apiClient } from '@/shared/http/client'
import type {
  ForkRouteRequest,
  ReanswerRouteRequest,
  RegenerateNodeRequest,
  RegenerateResponse,
  RouteLineageView,
  RouteMutationResponse,
} from '@/shared/contracts/types'

/**
 * 路线命令 + 谱系读取 API。
 *
 * 命令走既有的后端路线命令端点;前端从不在本地复现路线状态转移。
 * Fork/重新生成发送显式的 sourceRouteId 加用户控制的内容;
 * 运行时生成的 id(routeId、rootNodeId、tipNodeId、选项 id、生命周期状态)
 * 绝不包含在请求中。
 */

export function activateRoute(projectId: string, routeId: string): Promise<RouteMutationResponse> {
  return apiClient.post<RouteMutationResponse>(
    `/projects/${projectId}/routes/${routeId}/activate`,
  )
}

export function archiveRoute(projectId: string, routeId: string): Promise<RouteMutationResponse> {
  return apiClient.post<RouteMutationResponse>(
    `/projects/${projectId}/routes/${routeId}/archive`,
  )
}

export function restoreRoute(projectId: string, routeId: string): Promise<RouteMutationResponse> {
  return apiClient.post<RouteMutationResponse>(
    `/projects/${projectId}/routes/${routeId}/restore`,
  )
}

export function deleteRoute(projectId: string, routeId: string): Promise<RouteMutationResponse> {
  return apiClient.post<RouteMutationResponse>(
    `/projects/${projectId}/routes/${routeId}/delete`,
  )
}

export function forkNode(
  projectId: string,
  nodeId: string,
  payload: ForkRouteRequest,
): Promise<RouteMutationResponse> {
  return apiClient.post<RouteMutationResponse>(
    `/projects/${projectId}/nodes/${nodeId}/fork`,
    payload,
  )
}

/** 从一个浮动的知识/资源节点开启一条全新的独立路线:
 * 该节点成为路线的根+末端;下一个问题草稿锚定在此。
 * 节点保留自己的 id、kind 和内容。 */
export function startRouteFromNode(
  projectId: string,
  nodeId: string,
  label?: string | null,
): Promise<RouteMutationResponse> {
  return apiClient.post<RouteMutationResponse>(
    `/projects/${projectId}/nodes/${nodeId}/start-route`,
    { label: label ?? null },
  )
}

export function reanswerNode(
  projectId: string,
  nodeId: string,
  payload: ReanswerRouteRequest,
): Promise<RouteMutationResponse> {
  return apiClient.post<RouteMutationResponse>(
    `/projects/${projectId}/nodes/${nodeId}/reanswer`,
    payload,
  )
}

export function regenerateNode(
  projectId: string,
  nodeId: string,
  payload: RegenerateNodeRequest,
): Promise<RegenerateResponse> {
  return apiClient.post<RegenerateResponse>(
    `/projects/${projectId}/nodes/${nodeId}/regenerate`,
    payload,
  )
}

export function getRouteLineage(
  projectId: string,
  routeId: string,
): Promise<RouteLineageView> {
  return apiClient.get<RouteLineageView>(
    `/projects/${projectId}/routes/${routeId}/lineage`,
  )
}
