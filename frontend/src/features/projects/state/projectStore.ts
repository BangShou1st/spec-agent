import { defineStore } from 'pinia'
import { createProject, deleteProject, listProjects, renameProject } from '@/features/projects/api/projects'
import { toDisplayError, type DisplayError } from '@/shared/http/displayError'
import type { ProjectResponse, ProjectSummaryResponse } from '@/shared/contracts/types'

// Module-level monotonically increasing token for loadProjects race safety.
let loadToken = 0

/**
 * Project list/create application state. Backend remains authoritative for
 * everything; this store only mirrors list data and creation results.
 */
export const useProjectStore = defineStore('project', {
  state: () => ({
    projects: [] as ProjectSummaryResponse[],
    loading: false,
    creating: false,
    deletingId: null as string | null,
    renamingId: null as string | null,
    error: null as DisplayError | null,
  }),
  actions: {
    // Monotonic token so a slow, stale list response can never overwrite a
    // newer one. Typing triggers several in-flight requests; only the latest
    // wins. This is lighter than debouncing and keeps the list responsive.
    async loadProjects(title?: string): Promise<void> {
      const token = ++loadToken
      this.loading = true
      this.error = null
      try {
        const result = await listProjects(title)
        if (token === loadToken) {
          this.projects = result
        }
      } catch (err) {
        if (token === loadToken) {
          this.error = toDisplayError(err)
        }
      } finally {
        if (token === loadToken) {
          this.loading = false
        }
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

    /** Renames one project in place; list order and history stay untouched. */
    async renameProject(id: string, title: string): Promise<boolean> {
      if (this.renamingId) return false
      this.renamingId = id
      this.error = null
      try {
        const updated = await renameProject(id, title)
        this.projects = this.projects.map((p) => (p.id === id ? { ...p, title: updated.title, updatedAt: updated.updatedAt } : p))
        return true
      } catch (err) {
        this.error = toDisplayError(err)
        return false
      } finally {
        this.renamingId = null
      }
    },

    async deleteProject(id: string): Promise<boolean> {
      if (this.deletingId) return false
      this.deletingId = id
      this.error = null
      try {
        await deleteProject(id)
        this.projects = this.projects.filter((p) => p.id !== id)
        return true
      } catch (err) {
        this.error = toDisplayError(err)
        return false
      } finally {
        this.deletingId = null
      }
    },
  },
})
