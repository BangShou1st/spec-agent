import { defineStore } from 'pinia'
import { ApiError, GENERIC_ERROR_MESSAGE } from '@/api/client'
import {
  getOpenRouterStatus,
  listOpenRouterModels,
  probeOpenRouter,
  saveOpenRouter,
  validateOpenRouter,
} from '@/api/modelProviders'

export interface ProviderError {
  code: string
  message: string
}

function displayError(err: unknown): ProviderError {
  if (err instanceof ApiError) {
    return { code: err.code, message: err.message }
  }
  return { code: 'UNKNOWN_ERROR', message: GENERIC_ERROR_MESSAGE }
}

export const useOpenRouterStore = defineStore('openRouter', {
  state: () => ({
    configured: false,
    maskedKey: null as string | null,
    selectedModel: null as string | null,
    configRevision: 0,
    validated: false,
    freeModels: [] as string[],
    // True provider-available models from the last successful fetch.
    // freeModels is the display list (available + injected persisted for
    // visibility). Save gating must use availableModels, never the display
    // list, so an injected unavailable persisted value cannot be saved.
    availableModels: [] as string[],
    probing: false,
    saving: false,
    validating: false,
    loadingModels: false,
    modelUnavailable: false,
    failed: false,
    error: null as ProviderError | null,
  }),
  actions: {
    ensurePersistedVisible(): void {
      // A persisted selection must stay visible even before models are
      // (re)fetched. It is prepended as the saved value, never replaced
      // by silently picking another model.
      if (this.selectedModel && !this.freeModels.includes(this.selectedModel)) {
        this.freeModels = [this.selectedModel, ...this.freeModels]
      }
    },
    async loadStatus(): Promise<void> {
      this.error = null
      try {
        const s = await getOpenRouterStatus()
        this.configured = s.configured
        this.maskedKey = s.maskedKey
        this.selectedModel = s.selectedModel
        this.configRevision = s.configRevision
        this.validated = s.validated
        this.ensurePersistedVisible()
        this.failed = false
      } catch (err) {
        this.error = displayError(err)
      }
    },
    async probe(apiKey: string): Promise<string[]> {
      if (this.probing || !apiKey.trim()) {
        return []
      }
      this.probing = true
      this.error = null
      try {
        const res = await probeOpenRouter(apiKey.trim())
        this.availableModels = [...res.freeModels]
        this.freeModels = [...res.freeModels]
        this.selectedModel = null
        this.modelUnavailable = false
        this.failed = false
        return this.freeModels
      } catch (err) {
        this.error = displayError(err)
        this.failed = true
        return []
      } finally {
        this.probing = false
      }
    },
    async refreshModels(): Promise<void> {
      if (this.loadingModels) {
        return
      }
      this.loadingModels = true
      this.error = null
      try {
        const res = await listOpenRouterModels()
        this.availableModels = [...res.freeModels]
        this.freeModels = [...res.freeModels]
        this.ensurePersistedVisible()
        this.modelUnavailable = Boolean(
          this.configured && this.selectedModel && !res.freeModels.includes(this.selectedModel),
        )
        this.failed = false
      } catch (err) {
        this.error = displayError(err)
        // 401/403/429/5xx stay hard errors and never degrade to manual.
        this.failed = true
      } finally {
        this.loadingModels = false
      }
    },
    async save(apiKey: string | null, selectedModel: string): Promise<boolean> {
      if (this.saving) {
        return false
      }
      this.saving = true
      this.error = null
      try {
        const s = await saveOpenRouter(apiKey, selectedModel)
        this.configured = s.configured
        this.maskedKey = s.maskedKey
        this.selectedModel = s.selectedModel
        this.configRevision = s.configRevision
        this.validated = s.validated
        this.ensurePersistedVisible()
        this.failed = false
        return true
      } catch (err) {
        this.error = displayError(err)
        this.failed = true
        return false
      } finally {
        this.saving = false
      }
    },
    async validate(): Promise<boolean> {
      if (this.validating) {
        return false
      }
      this.validating = true
      this.error = null
      try {
        const s = await validateOpenRouter()
        this.configured = s.configured
        this.maskedKey = s.maskedKey
        this.selectedModel = s.selectedModel
        this.configRevision = s.configRevision
        this.validated = s.validated
        this.ensurePersistedVisible()
        this.failed = false
        return s.validated
      } catch (err) {
        this.error = displayError(err)
        this.failed = true
        this.validated = false
        return false
      } finally {
        this.validating = false
      }
    },
    clearError(): void {
      this.error = null
    },
  },
})
