import { defineStore } from 'pinia'
import { createProject, listProjects } from '@/api/projects'
import { ApiError, GENERIC_ERROR_MESSAGE } from '@/api/client'
import type { ProjectResponse, ProjectSummaryResponse } from '@/api/types'

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
 * Project list/create application state. Backend remains authoritative for
 * everything; this store only mirrors list data and creation results.
 */
export const useProjectStore = defineStore('project', {
  state: () => ({
    projects: [] as ProjectSummaryResponse[],
    loading: false,
    creating: false,
    error: null as DisplayError | null,
  }),
  actions: {
    async loadProjects(): Promise<void> {
      this.loading = true
      this.error = null
      try {
        this.projects = await listProjects()
      } catch (err) {
        this.error = toDisplayError(err)
      } finally {
        this.loading = false
      }
    },

    async createProject(title: string): Promise<ProjectResponse | null> {
      if (this.creating) {
        return null
      }
      this.creating = true
      this.error = null
      try {
        const project = await createProject(title)
        this.projects = [...this.projects, { ...project }]
        return project
      } catch (err) {
        this.error = toDisplayError(err)
        return null
      } finally {
        this.creating = false
      }
    },
  },
})