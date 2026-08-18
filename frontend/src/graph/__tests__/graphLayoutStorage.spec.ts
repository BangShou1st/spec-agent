import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  loadProjectGraphPreferences,
  loadWorkspaceUiPreferences,
  saveProjectGraphPreferences,
  saveWorkspaceUiPreferences,
  DEFAULT_WORKSPACE_UI,
} from '@/graph/graphLayoutStorage'
import type { ProjectGraphPreferencesV1, WorkspaceUiPreferencesV1 } from '@/graph/graphTypes'

const PROJECT_KEY = 'spec-agent.graph-layout.v1.p1'
const WORKSPACE_KEY = 'spec-agent.workspace-ui.v1'

describe('graph layout storage', () => {
  afterEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('returns defaults when nothing is stored', () => {
    const prefs = loadProjectGraphPreferences('p1')
    expect(prefs.version).toBe(1)
    expect(prefs.nodePositions).toEqual({})
    expect(prefs.routeDisplayStates).toEqual({})
  })

  it('returns defaults + persists defaults when storage holds invalid JSON', () => {
    localStorage.setItem(PROJECT_KEY, '{not json')
    expect(loadProjectGraphPreferences('p1')).toEqual({
      version: 1,
      nodePositions: {},
      routeDisplayStates: {},
    })
  })

  it('ignores non-finite node positions and keeps valid ones', () => {
    localStorage.setItem(
      PROJECT_KEY,
      JSON.stringify({
        version: 1,
        nodePositions: {
          n1: { x: 10, y: 20 },
          n2: { x: 'abc', y: null },
          n3: { x: 5, y: 'NaN' },
        },
        routeDisplayStates: {},
      }),
    )
    const prefs = loadProjectGraphPreferences('p1')
    expect(prefs.nodePositions).toEqual({ n1: { x: 10, y: 20 } })
  })

  it('clamps sidebar widths outside the allowed ranges', () => {
    localStorage.setItem(
      WORKSPACE_KEY,
      JSON.stringify({
        version: 1,
        leftSidebar: { open: true, width: 9999 },
        rightSidebar: { open: false, width: 1 },
      }),
    )
    const prefs = loadWorkspaceUiPreferences()
    expect(prefs.leftSidebar.width).toBeLessThanOrEqual(420)
    expect(prefs.rightSidebar.width).toBeGreaterThanOrEqual(300)
  })

  it('keeps stale ids in loaded state without throwing', () => {
    localStorage.setItem(
      PROJECT_KEY,
      JSON.stringify({
        version: 1,
        nodePositions: { ghostNode: { x: 1, y: 2 } },
        routeDisplayStates: { ghostRoute: 'dimmed' },
      }),
    )
    const prefs = loadProjectGraphPreferences('p1')
    expect(prefs.nodePositions.ghostNode).toEqual({ x: 1, y: 2 })
    expect(prefs.routeDisplayStates.ghostRoute).toBe('dimmed')
  })

  it('does not let localStorage read failures escape', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage blocked')
    })
    const prefs = loadProjectGraphPreferences('p1')
    expect(prefs.nodePositions).toEqual({})
  })

  it('does not let localStorage write failures escape', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage full')
    })
    expect(() =>
      saveProjectGraphPreferences('p1', {
        version: 1,
        nodePositions: { n1: { x: 1, y: 2 } },
        routeDisplayStates: {},
      }),
    ).not.toThrow()
    expect(() => saveWorkspaceUiPreferences(DEFAULT_WORKSPACE_UI)).not.toThrow()
  })

  it('round-trips saved project preferences', () => {
    const value: ProjectGraphPreferencesV1 = {
      version: 1,
      nodePositions: { n1: { x: 11, y: 22 } },
      routeDisplayStates: { r1: 'hidden' },
    }
    saveProjectGraphPreferences('p1', value)
    expect(loadProjectGraphPreferences('p1')).toEqual(value)
  })

  it('round-trips saved workspace ui preferences', () => {
    const value: WorkspaceUiPreferencesV1 = {
      version: 1,
      leftSidebar: { open: false, width: 300 },
      rightSidebar: { open: true, width: 500 },
    }
    saveWorkspaceUiPreferences(value)
    expect(loadWorkspaceUiPreferences()).toEqual(value)
  })
})
