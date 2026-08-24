import type {
  GraphPosition,
  GraphRouteDisplayState,
  ProjectGraphPreferencesV1,
  ProjectGraphPreferencesV2,
  FloatingWindowPreference,
  WorkspaceUiPreferencesV2,
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
const PROJECT_V2_KEY_PREFIX = 'spec-agent.graph-layout.v2.'
const WORKSPACE_KEY = 'spec-agent.workspace-ui.v1'
const WORKSPACE_V2_KEY = 'spec-agent.workspace-ui.v2'

export const LEFT_SIDEBAR_RANGE = { min: 220, max: 420, default: 280 }
export const RIGHT_SIDEBAR_RANGE = { min: 300, max: 600, default: 380 }

export const DEFAULT_WORKSPACE_UI: WorkspaceUiPreferencesV1 = {
  version: 1,
  leftSidebar: { open: true, width: LEFT_SIDEBAR_RANGE.default },
  rightSidebar: { open: true, width: RIGHT_SIDEBAR_RANGE.default },
}

export const FLOATING_WINDOW_RANGES = {
  routes: { minWidth: 260, maxWidth: 480, minHeight: 220, maxHeight: 760 },
  inspector: { minWidth: 320, maxWidth: 640, minHeight: 260, maxHeight: 820 },
} as const

export const DEFAULT_WORKSPACE_UI_V2: WorkspaceUiPreferencesV2 = {
  version: 2,
  windows: {
    routes: { x: 0, y: 72, width: 320, height: 560, open: true, positionMode: 'auto' },
    inspector: { x: 0, y: 72, width: 420, height: 640, open: true, positionMode: 'auto' },
  },
}

const LEGACY_DEFAULT_WORKSPACE_UI_V2: WorkspaceUiPreferencesV2 = {
  version: 2,
  windows: {
    routes: { x: 24, y: 72, width: 320, height: 560, open: true, positionMode: 'auto' },
    inspector: { x: 836, y: 72, width: 420, height: 640, open: true, positionMode: 'auto' },
  },
}

function emptyProjectPrefs(): ProjectGraphPreferencesV1 {
  return { version: 1, nodePositions: {}, routeDisplayStates: {} }
}

function emptyProjectPrefsV2(): ProjectGraphPreferencesV2 {
  return { version: 2, nodePositions: {}, routeDisplayStates: {} }
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

export function loadProjectGraphPreferencesV2(projectId: string): ProjectGraphPreferencesV2 {
  try {
    const raw = localStorage.getItem(PROJECT_V2_KEY_PREFIX + projectId)
    if (!raw) return emptyProjectPrefsV2()
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed !== 'object' || parsed === null) return emptyProjectPrefsV2()
    const candidate = parsed as Partial<ProjectGraphPreferencesV2>
    if (candidate.version !== 2) return emptyProjectPrefsV2()
    const nodePositions: Record<string, GraphPosition> = {}
    if (typeof candidate.nodePositions === 'object' && candidate.nodePositions !== null) {
      for (const [id, pos] of Object.entries(candidate.nodePositions)) {
        if (typeof pos !== 'object' || pos === null) continue
        const p = pos as Partial<GraphPosition>
        const x = Number(p.x)
        const y = Number(p.y)
        if (Number.isFinite(x) && Number.isFinite(y)) nodePositions[id] = { x, y }
      }
    }
    const routeDisplayStates: Record<string, GraphRouteDisplayState> = {}
    if (typeof candidate.routeDisplayStates === 'object' && candidate.routeDisplayStates !== null) {
      for (const [id, state] of Object.entries(candidate.routeDisplayStates)) {
        if (state === 'normal' || state === 'dimmed' || state === 'hidden') routeDisplayStates[id] = state
      }
    }
    return { version: 2, nodePositions, routeDisplayStates }
  } catch {
    return emptyProjectPrefsV2()
  }
}

export function saveProjectGraphPreferencesV2(
  projectId: string,
  value: ProjectGraphPreferencesV2,
): void {
  try {
    localStorage.setItem(PROJECT_V2_KEY_PREFIX + projectId, JSON.stringify(value))
  } catch {
    // Browser presentation state is best-effort only.
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

function clampFloatingWindow(
  value: Partial<FloatingWindowPreference> | undefined,
  fallback: FloatingWindowPreference,
  range: { minWidth: number; maxWidth: number; minHeight: number; maxHeight: number },
  legacyFallback: FloatingWindowPreference,
): FloatingWindowPreference {
  const hasPositionMode = value?.positionMode === 'auto' || value?.positionMode === 'manual'
  const matchesLegacyDefault =
    Number(value?.x) === legacyFallback.x &&
    Number(value?.y) === legacyFallback.y &&
    Number(value?.width) === legacyFallback.width &&
    Number(value?.height) === legacyFallback.height &&
    value?.open === legacyFallback.open
  return {
    x: Number.isFinite(Number(value?.x)) ? Number(value?.x) : fallback.x,
    y: Number.isFinite(Number(value?.y)) ? Number(value?.y) : fallback.y,
    width: clamp(Number(value?.width), range.minWidth, range.maxWidth, fallback.width),
    height: clamp(Number(value?.height), range.minHeight, range.maxHeight, fallback.height),
    open: typeof value?.open === 'boolean' ? value.open : fallback.open,
    // v2 did not persist an interaction mode. Repair its known defaults, but
    // preserve a non-default legacy placement as an intentional manual move.
    positionMode: hasPositionMode
      ? value.positionMode as 'auto' | 'manual'
      : matchesLegacyDefault ? 'auto' : 'manual',
  }
}

export function loadWorkspaceUiPreferencesV2(): WorkspaceUiPreferencesV2 {
  try {
    const raw = localStorage.getItem(WORKSPACE_V2_KEY)
    if (!raw) return structuredClone(DEFAULT_WORKSPACE_UI_V2)
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed !== 'object' || parsed === null) return structuredClone(DEFAULT_WORKSPACE_UI_V2)
    const candidate = parsed as Partial<WorkspaceUiPreferencesV2>
    if (candidate.version !== 2) return structuredClone(DEFAULT_WORKSPACE_UI_V2)
    return {
      version: 2,
      windows: {
        routes: clampFloatingWindow(
          candidate.windows?.routes,
          DEFAULT_WORKSPACE_UI_V2.windows.routes,
          FLOATING_WINDOW_RANGES.routes,
          LEGACY_DEFAULT_WORKSPACE_UI_V2.windows.routes,
        ),
        inspector: clampFloatingWindow(
          candidate.windows?.inspector,
          DEFAULT_WORKSPACE_UI_V2.windows.inspector,
          FLOATING_WINDOW_RANGES.inspector,
          LEGACY_DEFAULT_WORKSPACE_UI_V2.windows.inspector,
        ),
      },
    }
  } catch {
    return structuredClone(DEFAULT_WORKSPACE_UI_V2)
  }
}

export function saveWorkspaceUiPreferencesV2(value: WorkspaceUiPreferencesV2): void {
  try {
    localStorage.setItem(WORKSPACE_V2_KEY, JSON.stringify(value))
  } catch {
    // Best-effort only.
  }
}
