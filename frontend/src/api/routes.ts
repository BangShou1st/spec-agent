import { apiClient } from './client'
import type {
  ForkRouteRequest,
  ReanswerRouteRequest,
  RegenerateNodeRequest,
  RegenerateResponse,
  RouteLineageView,
  RouteMutationResponse,
} from './types'

/**
 * Route command + lineage read API.
 *
 * Commands go through the existing backend route command endpoints; the
 * frontend never reproduces route transitions locally. Fork/regenerate send
 * only user-controlled content — runtime-owned ids (routeId, rootNodeId,
 * tipNodeId, option ids, lifecycle status) are never included in requests.
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
