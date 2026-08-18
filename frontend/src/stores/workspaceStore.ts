import { defineStore } from 'pinia'
import { ApiError, GENERIC_ERROR_MESSAGE } from '@/api/client'
import { getRequirementState } from '@/api/requirementState'
import type { ProjectResponse, RouteResponse } from '@/api/types'
import type {
  ActiveProjectStateResponse,
  RequirementStateView,
  SubmitAnswerRequest,
} from '@/api/types'
import {
  draftNextQuestion,
  getActiveState,
  listRoutes,
  submitAnswer,
} from '@/api/workspace'
import { getProject } from '@/api/projects'

export interface DisplayError {
  code: string
  message: string
}

function toDisplayError(err: unknown): DisplayError {
  if (err instanceof ApiError) {
    return { code: err.code, message: err.message }
  }
  return { code: 'UNKNOWN_ERROR', message: GENERIC_ERROR_MESSAGE }
}

/**
 * Workspace application state. The frontend never reconstructs Runtime
 * history: after every command the canonical backend read APIs are refreshed
 * and this store only mirrors what the backend returned. RequirementState is
 * backend-derived and never promoted client-side.
 */
export const useWorkspaceStore = defineStore('workspace', {
  state: () => ({
    projectId: null as string | null,
    project: null as ProjectResponse | null,
    routes: [] as RouteResponse[],
    activeState: null as ActiveProjectStateResponse | null,
    requirementState: null as RequirementStateView | null,
    loading: false,
    refreshing: false,
    drafting: false,
    submitting: false,
    feedback: null as string | null,
    error: null as DisplayError | null,
  }),
  actions: {
    async loadWorkspace(projectId: string): Promise<void> {
      this.projectId = projectId
      this.loading = true
      this.error = null
      this.feedback = null
      try {
        const [project, activeState, routes, requirementState] = await Promise.all([
          getProject(projectId),
          getActiveState(projectId),
          listRoutes(projectId),
          getRequirementState(projectId),
        ])
        this.project = project
        this.activeState = activeState
        this.routes = routes
        this.requirementState = requirementState
      } catch (err) {
        this.error = toDisplayError(err)
      } finally {
        this.loading = false
      }
    },

    /** Re-reads backend-derived workspace views after a command. */
    async refreshWorkspace(): Promise<void> {
      if (!this.projectId || this.refreshing) {
        return
      }
      this.refreshing = true
      this.error = null
      try {
        const [activeState, routes, requirementState] = await Promise.all([
          getActiveState(this.projectId),
          listRoutes(this.projectId),
          getRequirementState(this.projectId),
        ])
        this.activeState = activeState
        this.routes = routes
        this.requirementState = requirementState
      } catch (err) {
        this.error = toDisplayError(err)
      } finally {
        this.refreshing = false
      }
    },

    /** Drafts the next question. Explicit user action only. */
    async draftQuestion(): Promise<boolean> {
      if (!this.projectId || this.drafting) {
        return false
      }
      this.drafting = true
      this.error = null
      try {
        await draftNextQuestion(this.projectId)
        this.feedback = 'Question drafted.'
        await this.refreshWorkspace()
        return true
      } catch (err) {
        this.error = toDisplayError(err)
        return false
      } finally {
        this.drafting = false
      }
    },

    /** Submits an answer; the backend owns validity, history, and next state. */
    async submitAnswer(payload: SubmitAnswerRequest): Promise<boolean> {
      if (!this.projectId || this.submitting) {
        return false
      }
      this.submitting = true
      this.error = null
      try {
        await submitAnswer(this.projectId, payload)
        this.feedback = 'Answer recorded.'
        await this.refreshWorkspace()
        return true
      } catch (err) {
        this.error = toDisplayError(err)
        return false
      } finally {
        this.submitting = false
      }
    },
  },
})