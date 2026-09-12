import { defineStore } from 'pinia'
import { ApiError, GENERIC_ERROR_MESSAGE } from '@/api/client'
import { activateProvider, getActiveProvider, type ModelProvider } from '@/api/modelProviders'

export interface ProviderSettingsError {
  code: string
  message: string
}

function displayError(err: unknown): ProviderSettingsError {
  if (err instanceof ApiError) {
    return { code: err.code, message: err.message }
  }
  return { code: 'UNKNOWN_ERROR', message: GENERIC_ERROR_MESSAGE }
}

export const useProviderSettingsStore = defineStore('providerSettings', {
  state: () => ({
    activeProvider: 'OPENCODE_ZEN' as ModelProvider,
    viewing: 'OPENCODE_ZEN' as ModelProvider,
    loading: false,
    activating: false,
    error: null as ProviderSettingsError | null,
  }),
  actions: {
    async loadActive(): Promise<void> {
      this.loading = true
      this.error = null
      try {
        const res = await getActiveProvider()
        this.activeProvider = res.activeProvider
      } catch (err) {
        this.error = displayError(err)
      } finally {
        this.loading = false
      }
    },
    view(provider: ModelProvider): void {
      // Viewing a tab never activates the runtime provider.
      this.viewing = provider
    },
    async activate(provider: ModelProvider): Promise<boolean> {
      if (this.activating) {
        return false
      }
      this.activating = true
      this.error = null
      try {
        const res = await activateProvider(provider)
        this.activeProvider = res.activeProvider
        return true
      } catch (err) {
        this.error = displayError(err)
        return false
      } finally {
        this.activating = false
      }
    },
    clearError(): void {
      this.error = null
    },
  },
})
