import type {
  GraphPosition,
  GraphRouteDisplayState,
  ProjectGraphPreferencesV1,
  WorkspaceUiPreferencesV1,
} from './graphTypes'

/**
 * Defensive localStorage persistence for graph layout and workspace UI.
 *
 * All helpers are best-effort: corrupt, stale, or unavailable storage must
 * never throw into Runtime flows. Invalid JSON falls back to defaults,
 * non-finite coordinates are ignored, out-of-range sidebar widths are
 * clamped, and every read/write failure is swallowed.
 */

const PROJECT_KEY_PREFIX = 'spec-agent.graph-layout.v1.'
const WORKSPACE_KEY = 'spec-agent.workspace-ui.v1'

export const LEFT_SIDEBAR_RANGE = { min: 220, max: 420, default: 280 }
export const RIGHT_SIDEBAR_RANGE = { min: 300, max: 600, default: 380 }

export const DEFAULT_WORKSPACE_UI: WorkspaceUiPreferencesV1 = {
  version: 1,
  leftSidebar: { open: true, width: LEFT_SIDEBAR_RANGE.default },
  rightSidebar: { open: true, width: RIGHT_SIDEBAR_RANGE.default },
}

function emptyProjectPrefs(): ProjectGraphPreferencesV1 {
  return { version: 1, nodePositions: {}, routeDisplayStates: {} }
}

function clamp(value: number, min: number, max: number, fallback: number): number {
  if (!Number.isFinite(value)) return fallback
  return Math.min(max, Math.max(min, value))
}

export function loadProjectGraphPreferences(projectId: string): ProjectGraphPreferencesV1 {
  try {
    const raw = localStorage.getItem(PROJECT_KEY_PREFIX + projectId)
    if (!raw) return emptyProjectPrefs()
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed !== 'object' || parsed === null) return emptyProjectPrefs()
    const candidate = parsed as Partial<ProjectGraphPreferencesV1>
    if (candidate.version !== 1) return emptyProjectPrefs()

    const nodePositions: Record<string, GraphPosition> = {}
    const rawPositions = candidate.nodePositions
    if (typeof rawPositions === 'object' && rawPositions !== null) {
      for (const [id, pos] of Object.entries(rawPositions)) {
        if (typeof pos !== 'object' || pos === null) continue
        const p = pos as Partial<GraphPosition>
        const x = Number(p.x)
        const y = Number(p.y)
        if (Number.isFinite(x) && Number.isFinite(y)) {
          nodePositions[id] = { x, y }
        }
      }
    }

    const routeDisplayStates: Record<string, GraphRouteDisplayState> = {}
    const rawStates = candidate.routeDisplayStates
    if (typeof rawStates === 'object' && rawStates !== null) {
      for (const [id, state] of Object.entries(rawStates)) {
        if (state === 'normal' || state === 'dimmed' || state === 'hidden') {
          routeDisplayStates[id] = state
        }
      }
    }

    return { version: 1, nodePositions, routeDisplayStates }
  } catch {
    return emptyProjectPrefs()
  }
}

export function saveProjectGraphPreferences(
  projectId: string,
  value: ProjectGraphPreferencesV1,
): void {
  try {
    localStorage.setItem(PROJECT_KEY_PREFIX + projectId, JSON.stringify(value))
  } catch {
    // Best-effort only: never block Runtime flows on storage failures.
  }
}

function parseWorkspaceUi(raw: string | null): WorkspaceUiPreferencesV1 {
  if (!raw) return DEFAULT_WORKSPACE_UI
  try {
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed !== 'object' || parsed === null) return DEFAULT_WORKSPACE_UI
    const candidate = parsed as Partial<WorkspaceUiPreferencesV1>
    if (candidate.version !== 1) return DEFAULT_WORKSPACE_UI
    const left = candidate.leftSidebar
    const right = candidate.rightSidebar
    return {
      version: 1,
      leftSidebar: {
        open: typeof left?.open === 'boolean' ? left.open : DEFAULT_WORKSPACE_UI.leftSidebar.open,
        width: clamp(
          Number(left?.width),
          LEFT_SIDEBAR_RANGE.min,
          LEFT_SIDEBAR_RANGE.max,
          LEFT_SIDEBAR_RANGE.default,
        ),
      },
      rightSidebar: {
        open: typeof right?.open === 'boolean' ? right.open : DEFAULT_WORKSPACE_UI.rightSidebar.open,
        width: clamp(
          Number(right?.width),
          RIGHT_SIDEBAR_RANGE.min,
          RIGHT_SIDEBAR_RANGE.max,
          RIGHT_SIDEBAR_RANGE.default,
        ),
      },
    }
  } catch {
    return DEFAULT_WORKSPACE_UI
  }
}

export function loadWorkspaceUiPreferences(): WorkspaceUiPreferencesV1 {
  try {
    return parseWorkspaceUi(localStorage.getItem(WORKSPACE_KEY))
  } catch {
    return DEFAULT_WORKSPACE_UI
  }
}

export function saveWorkspaceUiPreferences(value: WorkspaceUiPreferencesV1): void {
  try {
    localStorage.setItem(WORKSPACE_KEY, JSON.stringify(value))
  } catch {
    // Best-effort only.
  }
}
