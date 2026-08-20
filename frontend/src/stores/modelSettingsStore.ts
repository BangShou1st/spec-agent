import { defineStore } from 'pinia'
import { ApiError, GENERIC_ERROR_MESSAGE } from '@/api/client'
import {
  getOpenCodeSettings,
  listOpenCodeModels,
  probeOpenCode,
  saveOpenCode,
  saveOpenCodeModel,
} from '@/api/modelSettings'
import type { OpenCodeSettingsStatus } from '@/api/types'

export interface ModelSettingsError {
  code: string
  message: string
}

function displayError(err: unknown): ModelSettingsError {
  if (err instanceof ApiError) {
    return { code: err.code, message: err.message }
  }
  return { code: 'UNKNOWN_ERROR', message: GENERIC_ERROR_MESSAGE }
}

export const useModelSettingsStore = defineStore('modelSettings', {
  state: () => ({
    status: null as OpenCodeSettingsStatus | null,
    freeModels: [] as string[],
    selectedModel: null as string | null,
    loading: false,
    probing: false,
    saving: false,
    loadingModels: false,
    changingCredential: false,
    modelUnavailable: false,
    error: null as ModelSettingsError | null,
  }),
  actions: {
    async loadStatus(): Promise<void> {
      this.loading = true
      this.error = null
      try {
        this.status = await getOpenCodeSettings()
        this.selectedModel = this.status.selectedModel
        this.freeModels = []
        this.modelUnavailable = false
        // The guard keeps older isolated test doubles compatible while the
        // production module always exposes the saved-key endpoint.
        if (this.status.configured && typeof listOpenCodeModels === 'function') {
          await this.refreshModels()
        }
      } catch (err) {
        this.error = displayError(err)
      } finally {
        this.loading = false
      }
    },

    async probe(apiKey: string): Promise<string[]> {
      if (this.probing || !apiKey.trim()) return []
      this.probing = true
      this.error = null
      try {
        const result = await probeOpenCode(apiKey.trim())
        this.freeModels = [...result.freeModels]
        // A probe discovers choices but never picks one for the user.
        this.selectedModel = null
        this.changingCredential = true
        this.modelUnavailable = false
        return this.freeModels
      } catch (err) {
        // Keep the previous working status and previous successful model list
        // untouched when a candidate probe fails.
        this.error = displayError(err)
        return []
      } finally {
        this.probing = false
      }
    },

    async save(apiKey: string, selectedModel: string): Promise<boolean> {
      if (this.saving || !apiKey.trim() || !selectedModel) return false
      this.saving = true
      this.error = null
      try {
        this.status = await saveOpenCode(apiKey.trim(), selectedModel)
        this.freeModels = []
        this.selectedModel = this.status.selectedModel
        this.changingCredential = false
        this.modelUnavailable = false
        await this.refreshModels()
        return true
      } catch (err) {
        this.error = displayError(err)
        return false
      } finally {
        this.saving = false
      }
    },

    async refreshModels(): Promise<boolean> {
      if (this.loadingModels || typeof listOpenCodeModels !== 'function') return false
      this.loadingModels = true
      this.error = null
      try {
        const result = await listOpenCodeModels()
        this.freeModels = [...result.freeModels]
        this.modelUnavailable = Boolean(
          this.status?.selectedModel && !this.freeModels.includes(this.status.selectedModel),
        )
        this.selectedModel = this.status?.selectedModel ?? null
        return true
      } catch (err) {
        this.error = displayError(err)
        return false
      } finally {
        this.loadingModels = false
      }
    },

    async saveModel(selectedModel: string): Promise<boolean> {
      if (this.saving || !this.status?.configured || !selectedModel) return false
      this.saving = true
      this.error = null
      try {
        this.status = await saveOpenCodeModel(selectedModel)
        this.selectedModel = this.status.selectedModel
        this.modelUnavailable = false
        return true
      } catch (err) {
        this.error = displayError(err)
        return false
      } finally {
        this.saving = false
      }
    },

    beginCredentialChange(): void {
      this.changingCredential = true
      this.freeModels = []
      this.selectedModel = null
      this.modelUnavailable = false
      this.error = null
    },

    clearError(): void {
      this.error = null
    },

    resetProbe(): void {
      this.freeModels = []
      this.selectedModel = null
      this.changingCredential = false
      this.modelUnavailable = false
      this.error = null
    },
  },
})
