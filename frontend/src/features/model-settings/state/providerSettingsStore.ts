// 文件名:providerSettingsStore.ts
// 用途:Provider 设置区的全局状态仓:当前激活 Provider 的加载与切换,
//       以及设置页内"正在查看哪个配置 Tab"的会话级状态(查看绝不等于激活)。

import { defineStore } from 'pinia'
import { identityScopedStore, privateIdentityWork, PrivateIdentityExpiredError } from '@/shared/state/privateIdentity'
import { toDisplayError } from '@/shared/http/displayError'
import { activateProvider, getActiveProvider, type ModelProvider } from '@/features/model-settings/api/modelProviders'

export interface ProviderSettingsError {
  code: string
  message: string
}

const displayError: (err: unknown) => ProviderSettingsError = toDisplayError

export const useProviderSettingsStore = defineStore('providerSettings', {
  state: () => ({
    identityEpoch: 0,
    privateIdentity: null as string | null,
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
    bindIdentity(identity: string | null): void {
      if (identity === this.privateIdentity && identity !== null) return
      const epoch = this.identityEpoch + 1
      this.$reset()
      this.identityEpoch = epoch
      this.privateIdentity = identity
    },
    async loadActive(): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      identityStore.loading = true
      identityStore.error = null
      try {
        const res = await privateIdentityWork(identityStore, identityEpoch, () => (getActiveProvider()))
        identityStore.activeProvider = res.activeProvider
        // 首次进入（本会话没手动选过 Tab）时，直接展示当前正在使用的
        // Provider 卡片，而不是永远停在 OpenCode Zen。
        if (!identityStore.viewTouched) {
          identityStore.viewing = res.activeProvider
        }
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

        identityStore.error = displayError(err)
      } finally {
        if (this.identityEpoch === identityEpoch) {
        identityStore.loading = false
      
        }
      }
    
      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return }
        throw identityError
      }
    },
    view(provider: ModelProvider): void {
      // 查看某个 Tab 绝不会激活运行时 Provider。
      this.viewing = provider
      this.viewTouched = true
    },
    async activate(provider: ModelProvider): Promise<boolean> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      if (identityStore.activating) {
        return false
      }
      identityStore.activating = true
      identityStore.error = null
      try {
        const res = await privateIdentityWork(identityStore, identityEpoch, () => (activateProvider(provider)))
        identityStore.activeProvider = res.activeProvider
        return true
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

        identityStore.error = displayError(err)
        return false
      } finally {
        if (this.identityEpoch === identityEpoch) {
        identityStore.activating = false
      
        }
      }
    
      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return false }
        throw identityError
      }
    },
    clearError(): void {
      this.error = null
    },
  },
})
