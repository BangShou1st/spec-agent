import { defineStore } from 'pinia'
import { toDisplayError } from '@/api/displayError'
import { activateProvider, getActiveProvider, type ModelProvider } from '@/api/modelProviders'

export interface ProviderSettingsError {
  code: string
  message: string
}

const displayError: (err: unknown) => ProviderSettingsError = toDisplayError

export const useProviderSettingsStore = defineStore('providerSettings', {
  state: () => ({
    activeProvider: 'OPENCODE_ZEN' as ModelProvider,
    // Custom 通过末尾的 “+” 弹窗进入，不再是常驻 Tab；默认看第一个预设。
    viewing: 'OPENCODE_ZEN' as ModelProvider,
    // 用户在本会话里手动选过 Tab 之后，进入设置页不再自动跳到当前激活项。
    viewTouched: false,
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
        // 首次进入（本会话没手动选过 Tab）时，直接展示当前正在使用的
        // Provider 卡片，而不是永远停在 OpenCode Zen。
        if (!this.viewTouched) {
          this.viewing = res.activeProvider
        }
      } catch (err) {
        this.error = displayError(err)
      } finally {
        this.loading = false
      }
    },
    view(provider: ModelProvider): void {
      // Viewing a tab never activates the runtime provider.
      this.viewing = provider
      this.viewTouched = true
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
