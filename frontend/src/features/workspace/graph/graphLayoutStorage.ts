// 文件名:graphLayoutStorage.ts
// 用途:画布布局与工作台 UI 偏好的 localStorage 防御式持久化:节点位置、路线显示状态、侧栏宽度等,读写全部 best-effort,绝不向运行时流程抛错。
/*
 * 图布局与工作台 UI 的防御式 localStorage 持久化。
 *
 * 所有辅助函数都是 best-effort:损坏、过期或不可用的存储绝不能向运行时
 * 流程抛异常。非法 JSON 回退到默认值,非有限坐标被忽略,超出范围的侧栏
 * 宽度被钳制,每次读写失败都被静默吞掉。
 */

import type {
  GraphPosition,
  GraphRouteDisplayState,
  ProjectGraphPreferencesV1,
  ProjectGraphPreferencesV2,
  WorkspaceUiPreferencesV1,
} from './graphTypes'


const PROJECT_KEY_PREFIX = 'spec-agent.graph-layout.v1.'
const PROJECT_V2_KEY_PREFIX = 'spec-agent.graph-layout.v2.'
const WORKSPACE_KEY = 'spec-agent.workspace-ui.v1'

export const LEFT_SIDEBAR_RANGE = { min: 220, max: 420, default: 236 }
export const RIGHT_SIDEBAR_RANGE = { min: 300, max: 600, default: 332 }

export const DEFAULT_WORKSPACE_UI: WorkspaceUiPreferencesV1 = {
  version: 1,
  leftSidebar: { open: true, width: LEFT_SIDEBAR_RANGE.default },
  rightSidebar: { open: true, width: RIGHT_SIDEBAR_RANGE.default },
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
    // 仅 best-effort:存储失败绝不能阻塞运行时流程。
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
    // 浏览器展示状态,仅 best-effort。
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
    // 仅 best-effort。
  }
}

