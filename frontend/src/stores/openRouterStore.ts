import { defineStore } from 'pinia'
import { ApiError, GENERIC_ERROR_MESSAGE } from '@/api/client'
import {
  getOpenRouterStatus,
  listOpenRouterModels,
  probeOpenRouter,
  saveOpenRouter,
  validateOpenRouter,
} from '@/api/modelProviders'

/** Mirrors the backend free-id policy: openrouter/free or the :free suffix. */
function isFreeModelId(id: string): boolean {
  return id === 'openrouter/free' || id.endsWith(':free')
}

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
    // Full provider catalog (free and paid) plus the free subset; the UI
    // defaults to the full list and can toggle "仅免费" to filter.
    allModels: [] as string[],
    freeModels: [] as string[],
    freeOnly: false,
    // True provider-available models from the last successful fetch.
    // freeModels is the display list (available + injected persisted for
    // visibility). Save gating must use availableModels, never the display
    // list, so an injected unavailable persisted value cannot be saved.
    availableModels: [] as string[],
    /** 首次状态加载中：此时列表为空只代表「还没到」，不代表「没有」。 */
    loading: false,
    probing: false,
    saving: false,
    validating: false,
    loadingModels: false,
    modelUnavailable: false,
    failed: false,
    error: null as ProviderError | null,
  }),
  getters: {
    /** Display list: full catalog by default, free-only when toggled. */
    displayModels(state): string[] {
      return state.freeOnly
      && state.freeModels.length > 0 ? state.freeModels : state.allModels
    },
  },
  actions: {
    ensurePersistedVisible(): void {
      // A persisted selection must stay visible even before models are
      // (re)fetched. It is prepended as the saved value, never replaced
      // by silently picking another model.
      if (this.selectedModel && !this.allModels.includes(this.selectedModel)) {
        this.allModels = [this.selectedModel, ...this.allModels]
      }
      if (this.selectedModel && isFreeModelId(this.selectedModel)
        && !this.freeModels.includes(this.selectedModel)) {
        this.freeModels = [this.selectedModel, ...this.freeModels]
      }
    },
    setFreeOnly(value: boolean): void {
      this.freeOnly = value
    },
    async loadStatus(): Promise<void> {
      this.loading = true
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
      } finally {
        this.loading = false
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
        // 兼容旧后端：没有 allModels 时回退到 freeModels（只展示免费列表）。
        const all = Array.isArray(res.allModels) ? res.allModels : res.freeModels
        this.availableModels = [...all]
        this.allModels = [...all]
        this.freeModels = [...(res.freeModels ?? [])]
        this.selectedModel = null
        this.modelUnavailable = false
        this.failed = false
        return this.allModels
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
        // 兼容旧后端：没有 allModels 时回退到 freeModels（只展示免费列表）。
        const all = Array.isArray(res.allModels) ? res.allModels : res.freeModels
        this.availableModels = [...all]
        this.allModels = [...all]
        this.freeModels = [...(res.freeModels ?? [])]
        // Unavailability is judged against the true live list BEFORE the
        // persisted value is prepended for visibility, so an injected stale
        // selection still surfaces as unavailable.
        const visible = this.freeOnly && this.freeModels.length > 0 ? this.freeModels : this.allModels
        this.modelUnavailable = Boolean(
          this.configured && this.selectedModel && !visible.includes(this.selectedModel),
        )
        this.ensurePersistedVisible()
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
