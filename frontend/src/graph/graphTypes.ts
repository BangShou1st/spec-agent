/**
 * Browser-only graph workspace UI types.
 *
 * These types describe view state only: selection, focus, dim/hide, local
 * node positions and sidebar layout. They never describe Runtime facts and
 * are never sent to the backend.
 */

/** Manual per-route visual display state (browser-only). */
export type GraphRouteDisplayState = 'normal' | 'dimmed' | 'hidden'

export interface GraphPosition {
  x: number
  y: number
}

/** Per-project graph layout preferences persisted locally. */
export interface ProjectGraphPreferencesV1 {
  version: 1
  nodePositions: Record<string, GraphPosition>
  routeDisplayStates: Record<string, GraphRouteDisplayState>
}

/** V2 presentation namespace: positions are keyed by visual graph identity. */
export interface ProjectGraphPreferencesV2 {
  version: 2
  nodePositions: Record<string, GraphPosition>
  routeDisplayStates: Record<string, GraphRouteDisplayState>
}

/** Global workspace UI preferences persisted locally. */
export interface WorkspaceUiPreferencesV1 {
  version: 1
  leftSidebar: { open: boolean; width: number }
  rightSidebar: { open: boolean; width: number }
}

export interface FloatingWindowPreference {
  x: number
  y: number
  width: number
  height: number
  open: boolean
}

export interface WorkspaceUiPreferencesV2 {
  version: 2
  windows: {
    routes: FloatingWindowPreference
    inspector: FloatingWindowPreference
  }
}
