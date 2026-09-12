import { defineStore } from 'pinia'
import { ApiError, GENERIC_ERROR_MESSAGE } from '@/api/client'
import {
  discoverCustom,
  getCustomStatus,
  saveCustomWithSource,
  validateCustom,
  type CustomApiFormat,
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

export const useCustomProviderStore = defineStore('customProvider', {
  state: () => ({
    configured: false,
    apiFormat: 'CHAT_COMPLETIONS' as CustomApiFormat,
    baseUrl: '',
    endpointPreview: null as string | null,
    hasKey: false,
    maskedKey: null as string | null,
    selectedModel: '' as string,
    discoveredModels: [] as string[],
    // True available models from the last successful discover.
    // discoveredModels is the display list (available + injected persisted).
    // Save gating must use availableModels for discovered mode; manual mode
    // is unaffected.
    availableModels: [] as string[],
    manualModel: false,
    configRevision: 0,
    validated: false,
    discovering: false,
    saving: false,
    validating: false,
    modelUnavailable: false,
    failed: false,
    error: null as ProviderError | null,
  }),
  actions: {
    ensurePersistedVisible(): void {
      // Same rule as OpenRouter: the persisted selection stays visible
      // before (re)fetch; manual mode is driven by persisted modelSource.
      if (!this.manualModel && this.selectedModel
        && !this.discoveredModels.includes(this.selectedModel)) {
        this.discoveredModels = [this.selectedModel, ...this.discoveredModels]
      }
    },
    async loadStatus(): Promise<void> {
      this.error = null
      try {
        const s = await getCustomStatus()
        this.configured = s.configured
        this.apiFormat = s.apiFormat
        this.baseUrl = s.baseUrl ?? ''
        this.endpointPreview = s.endpointPreview
        this.hasKey = s.hasKey
        this.maskedKey = s.maskedKey
        this.selectedModel = s.selectedModel ?? ''
        this.manualModel = s.manualModel
        this.configRevision = s.configRevision
        this.validated = s.validated
        this.ensurePersistedVisible()
        this.failed = false
      } catch (err) {
        this.error = displayError(err)
      }
    },
    setFormat(format: CustomApiFormat): void {
      if (this.apiFormat !== format) {
        this.apiFormat = format
        // Changing format invalidates discovery + validation presentation.
        this.discoveredModels = []
        this.availableModels = []
        this.manualModel = false
        this.ensurePersistedVisible()
        this.validated = false
      }
    },
    async discover(apiKey?: string | null): Promise<void> {
      if (this.discovering || !this.baseUrl.trim()) {
        return
      }
      this.discovering = true
      this.error = null
      try {
        const res = await discoverCustom(this.apiFormat, this.baseUrl.trim(), apiKey ?? null)
        this.availableModels = [...res.models]
        this.discoveredModels = [...res.models]
        this.manualModel = res.manualModel
        this.endpointPreview = res.endpointPreview
        this.ensurePersistedVisible()
        this.modelUnavailable = Boolean(
          !res.manualModel && this.selectedModel && !res.models.includes(this.selectedModel),
        )
        this.failed = false
      } catch (err) {
        this.error = displayError(err)
        this.failed = true
        // Auth/network failures never degrade to manual fallback.
        this.manualModel = false
      } finally {
        this.discovering = false
      }
    },
    async save(apiKey: string | null | undefined): Promise<boolean> {
      if (this.saving) {
        return false
      }
      this.saving = true
      this.error = null
      try {
        const s = await saveCustomWithSource(this.apiFormat, this.baseUrl.trim(), apiKey,
          this.selectedModel.trim(), this.manualModel ? 'MANUAL' : 'DISCOVERED')
        this.configured = s.configured
        this.apiFormat = s.apiFormat
        this.baseUrl = s.baseUrl
        this.endpointPreview = s.endpointPreview
        this.hasKey = s.hasKey
        this.maskedKey = s.maskedKey
        this.selectedModel = s.selectedModel
        this.manualModel = s.manualModel
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
        const s = await validateCustom()
        this.configured = s.configured
        this.apiFormat = s.apiFormat
        this.baseUrl = s.baseUrl
        this.endpointPreview = s.endpointPreview
        this.hasKey = s.hasKey
        this.maskedKey = s.maskedKey
        this.selectedModel = s.selectedModel
        this.manualModel = s.manualModel
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
