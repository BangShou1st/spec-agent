import { defineStore } from 'pinia'
import { ApiError, GENERIC_ERROR_MESSAGE } from '@/api/client'
import { getOpenCodeSettings, probeOpenCode, saveOpenCode } from '@/api/modelSettings'
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
    error: null as ModelSettingsError | null,
  }),
  actions: {
    async loadStatus(): Promise<void> {
      this.loading = true
      this.error = null
      try {
        this.status = await getOpenCodeSettings()
        this.selectedModel = null
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
        this.selectedModel = null
        return true
      } catch (err) {
        this.error = displayError(err)
        return false
      } finally {
        this.saving = false
      }
    },

    clearError(): void {
      this.error = null
    },

    resetProbe(): void {
      this.freeModels = []
      this.selectedModel = null
      this.error = null
    },
  },
})
