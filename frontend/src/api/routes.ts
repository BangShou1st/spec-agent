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
 * explicit sourceRouteId plus user-controlled content; runtime-generated ids
 * (routeId, rootNodeId, tipNodeId, option ids, lifecycle status) are never
 * included in requests.
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

/** Starts a NEW standalone route from a floating knowledge/resource node:
 * the node becomes the route's root+tip; the next question draft anchors
 * there. The node keeps its id, kind and content. */
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
