// 文件名:kiloStore.ts
// 用途:Kilo Gateway Provider 的 Pinia 状态仓:设置状态加载、密钥探测、模型列表刷新、
//       保存(密钥+模型)/改模型/校验,以及"已存选中项在拉取前保持可见"等展示规则。
//       与 OpenCode Zen 卡对齐:保存即证明可达,因此会话内初始视为已校验。

import { defineStore } from 'pinia'
import { identityScopedStore, privateIdentityWork, PrivateIdentityExpiredError } from '@/shared/state/privateIdentity'
import { toDisplayError } from '@/shared/http/displayError'
import {
  getKiloSettings,
  listKiloModels,
  probeKilo,
  saveKilo,
  saveKiloModel,
  validateKilo,
} from '@/features/model-settings/api/kiloSettings'
import type { KiloSettingsStatus } from '@/shared/contracts/types'

export interface KiloSettingsError {
  code: string
  message: string
}

const displayError: (err: unknown) => KiloSettingsError = toDisplayError

export const useKiloSettingsStore = defineStore('kiloSettings', {
  state: () => ({
    identityEpoch: 0,
    privateIdentity: null as string | null,
    status: null as KiloSettingsStatus | null,
    // 暴露的全部模型(免费+付费)及免费子集;UI 默认展示完整列表,
    // 可通过"仅免费"开关过滤。
    allModels: [] as string[],
    freeModels: [] as string[],
    freeOnly: false,
    selectedModel: null as string | null,
    loading: false,
    probing: false,
    saving: false,
    validating: false,
    loadingModels: false,
    changingCredential: false,
    modelUnavailable: false,
    /**
     * Kilo 没有持久化的"已校验版本"列:save/changeModel 在唯一一次 upsert
     * 之前就已证明可达性,因此已存的(密钥,模型)对天然视为已校验。此标志只在
     * 会话内有效,仅用于让卡片能呈现三种状态。
     */
    validated: false,
    error: null as KiloSettingsError | null,
  }),
  getters: {
    /** 展示列表:默认完整目录,开启"仅免费"后为免费子集。 */
    displayModels(state): string[] {
      return state.freeOnly ? state.freeModels : state.allModels
    },
  },
  actions: {
    bindIdentity(identity: string | null): void {
      if (identity === this.privateIdentity && identity !== null) return
      const epoch = this.identityEpoch + 1
      this.$reset()
      this.identityEpoch = epoch
      this.privateIdentity = identity
    },
    /** 已保存的选中模型在(重新)拉取列表之前必须保持可见,避免闪出空态。 */
    ensurePersistedVisible(): void {
      const persisted = this.status?.selectedModel
      if (!persisted) {
        return
      }
      if (!this.allModels.includes(persisted)) {
        this.allModels = [persisted, ...this.allModels]
      }
    },
    async loadStatus(): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
        identityStore.loading = true
        identityStore.error = null
        try {
          identityStore.status = await privateIdentityWork(identityStore, identityEpoch, () => (getKiloSettings()))
          identityStore.selectedModel = identityStore.status.selectedModel
          identityStore.allModels = []
          identityStore.freeModels = []
          identityStore.modelUnavailable = false
          identityStore.validated = identityStore.status.configured
          identityStore.ensurePersistedVisible()
          await privateIdentityWork(identityStore, identityEpoch, () => (identityStore.refreshModels()))
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

    async probe(apiKey: string): Promise<string[]> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
        if (identityStore.probing) return []
        identityStore.probing = true
        identityStore.error = null
        try {
          const result = await privateIdentityWork(identityStore, identityEpoch, () => (probeKilo(apiKey.trim())))
          // 兼容旧后端：没有 allModels 时回退到 freeModels（只展示免费列表）。
          const all = Array.isArray(result.allModels) ? result.allModels : result.freeModels
          identityStore.allModels = [...all]
          identityStore.freeModels = [...(result.freeModels ?? [])]
          // 探测只负责发现可选项,绝不替用户做选择。
          identityStore.selectedModel = null
          identityStore.changingCredential = true
          identityStore.modelUnavailable = false
          return identityStore.freeModels
        } catch (err) {
          if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

          identityStore.error = displayError(err)
          return []
        } finally {
          if (this.identityEpoch === identityEpoch) {
            identityStore.probing = false

          }
        }

      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return [] }
        throw identityError
      }
    },

    async save(apiKey: string, selectedModel: string): Promise<boolean> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
        if (identityStore.saving || !selectedModel) return false
        identityStore.saving = true
        identityStore.error = null
        try {
          identityStore.status = await privateIdentityWork(identityStore, identityEpoch, () => (saveKilo(apiKey.trim() || null, selectedModel)))
          identityStore.allModels = []
          identityStore.freeModels = []
          identityStore.selectedModel = identityStore.status.selectedModel
          identityStore.changingCredential = false
          identityStore.modelUnavailable = false
          // 服务端在 upsert 之前已对这一对(密钥,模型)重新校验过。
          identityStore.validated = true
          await privateIdentityWork(identityStore, identityEpoch, () => (identityStore.refreshModels()))
          return true
        } catch (err) {
          if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

          identityStore.error = displayError(err)
          return false
        } finally {
          if (this.identityEpoch === identityEpoch) {
            identityStore.saving = false

          }
        }

      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return false }
        throw identityError
      }
    },

    async refreshModels(): Promise<boolean> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
        if (identityStore.loadingModels) return false
        identityStore.loadingModels = true
        identityStore.error = null
        try {
          const result = await privateIdentityWork(identityStore, identityEpoch, () => (listKiloModels()))
          const all = Array.isArray(result.allModels) ? result.allModels : result.freeModels
          identityStore.allModels = [...all]
          identityStore.freeModels = [...(result.freeModels ?? [])]
          identityStore.modelUnavailable = Boolean(
            identityStore.status?.selectedModel && !identityStore.allModels.includes(identityStore.status.selectedModel),
          )
          identityStore.selectedModel = identityStore.status?.selectedModel ?? null
          identityStore.ensurePersistedVisible()
          return true
        } catch (err) {
          if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

          identityStore.error = displayError(err)
          return false
        } finally {
          if (this.identityEpoch === identityEpoch) {
            identityStore.loadingModels = false

          }
        }

      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return false }
        throw identityError
      }
    },

    async saveModel(selectedModel: string): Promise<boolean> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
        if (identityStore.saving || !identityStore.status?.configured || !selectedModel) return false
        identityStore.saving = true
        identityStore.error = null
        try {
          identityStore.status = await privateIdentityWork(identityStore, identityEpoch, () => (saveKiloModel(selectedModel)))
          identityStore.selectedModel = identityStore.status.selectedModel
          identityStore.modelUnavailable = false
          identityStore.validated = true
          return true
        } catch (err) {
          if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

          identityStore.error = displayError(err)
          return false
        } finally {
          if (this.identityEpoch === identityEpoch) {
            identityStore.saving = false

          }
        }

      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return false }
        throw identityError
      }
    },

    /** 对已存的(密钥,模型)对做显式的可达性重测;绝不修改已保存的配置。 */
    async validate(): Promise<boolean> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
        if (identityStore.validating) {
          return false
        }
        identityStore.validating = true
        identityStore.error = null
        try {
          identityStore.status = await privateIdentityWork(identityStore, identityEpoch, () => (validateKilo()))
          identityStore.selectedModel = identityStore.status.selectedModel
          identityStore.validated = true
          return true
        } catch (err) {
          if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

          identityStore.error = displayError(err)
          identityStore.validated = false
          return false
        } finally {
          if (this.identityEpoch === identityEpoch) {
            identityStore.validating = false

          }
        }

      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return false }
        throw identityError
      }
    },

    beginCredentialChange(): void {
      this.changingCredential = true
      this.error = null
    },

    cancelCredentialChange(): void {
      if (!this.status?.configured) {
        this.resetProbe()
        return
      }
      this.changingCredential = false
      this.allModels = []
      this.freeModels = []
      this.selectedModel = this.status.selectedModel
      this.modelUnavailable = false
      this.error = null
    },

    clearError(): void {
      this.error = null
    },

    setFreeOnly(value: boolean): void {
      this.freeOnly = value
    },

    resetProbe(): void {
      this.allModels = []
      this.freeModels = []
      this.selectedModel = null
      this.changingCredential = false
      this.modelUnavailable = false
      this.validated = false
      this.error = null
    },
  },
})
