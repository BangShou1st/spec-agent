// 文件名:customProviderStore.ts
// 用途:自定义 Provider 的 Pinia 状态仓:状态加载、模型发现、保存(含显示名称与
//       modelSource)、兼容性校验;维护"可用列表 vs 展示列表"与已存选中项的可见性规则。

import { defineStore } from 'pinia'
import { identityScopedStore, privateIdentityWork, PrivateIdentityExpiredError } from '@/shared/state/privateIdentity'
import { toDisplayError } from '@/shared/http/displayError'
import { classifyProviderProbeFailure } from '@/shared/http/providerReachability'
import {
  discoverCustom,
  getCustomStatus,
  saveCustomWithSource,
  validateCustom,
  type CustomApiFormat,
} from '@/features/model-settings/api/modelProviders'

export interface ProviderError {
  code: string
  message: string
}

const displayError: (err: unknown) => ProviderError = toDisplayError

export const useCustomProviderStore = defineStore('customProvider', {
  state: () => ({
    identityEpoch: 0,
    privateIdentity: null as string | null,
    configured: false,
    apiFormat: 'CHAT_COMPLETIONS' as CustomApiFormat,
    baseUrl: '',
    /** 最近一次已持久化的 Base URL;凭据复用守卫据此判断"地址换了来源"。 */
    savedBaseUrl: null as string | null,
    endpointPreview: null as string | null,
    hasKey: false,
    maskedKey: null as string | null,
    selectedModel: '' as string,
    discoveredModels: [] as string[],
    // 最近一次成功 discover 得到的真实可用模型。
    // discoveredModels 是展示列表(可用列表 + 注入的已持久化值)。
    // 发现模式的保存门槛必须用 availableModels;手动模式不受影响。
    availableModels: [] as string[],
    manualModel: false,
    /** 后端返回的状态胶囊标签;渲染时回退为 'Custom'。 */
    displayName: null as string | null,
    configRevision: 0,
    validated: false,
    discovering: false,
    saving: false,
    validating: false,
    modelUnavailable: false,
    failed: false,
    /** 上游可达但当前被限流:连通性判定通过,限流状态本身必须保留。 */
    rateLimited: false,
    error: null as ProviderError | null,
  }),
  actions: {
    bindIdentity(identity: string | null): void {
      if (identity === this.privateIdentity && identity !== null) return
      const epoch = this.identityEpoch + 1
      this.$reset()
      this.identityEpoch = epoch
      this.privateIdentity = identity
    },
    ensurePersistedVisible(): void {
      // 与 其他 Provider 相同的规则:(重新)拉取之前已持久化的选中项保持可见;
      // 手动模式由持久化的 modelSource 驱动。
      if (!this.manualModel && this.selectedModel
        && !this.discoveredModels.includes(this.selectedModel)) {
        this.discoveredModels = [this.selectedModel, ...this.discoveredModels]
      }
    },
    async loadStatus(): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      identityStore.error = null
      try {
        const s = await privateIdentityWork(identityStore, identityEpoch, () => (getCustomStatus()))
        identityStore.configured = s.configured
        identityStore.apiFormat = s.apiFormat
        identityStore.baseUrl = s.baseUrl ?? ''
        identityStore.savedBaseUrl = s.baseUrl ?? null
        identityStore.endpointPreview = s.endpointPreview
        identityStore.hasKey = s.hasKey
        identityStore.maskedKey = s.maskedKey
        identityStore.selectedModel = s.selectedModel ?? ''
        identityStore.manualModel = s.manualModel
        identityStore.displayName = s.displayName
        identityStore.configRevision = s.configRevision
        identityStore.validated = s.validated
        identityStore.ensurePersistedVisible()
        identityStore.failed = false
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

        identityStore.error = displayError(err)
      }
    
      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return }
        throw identityError
      }
    },
    setFormat(format: CustomApiFormat): void {
      if (this.apiFormat !== format) {
        this.apiFormat = format
        // 更换格式会使发现与校验的展示结果失效。
        this.discoveredModels = []
        this.availableModels = []
        this.manualModel = false
        this.ensurePersistedVisible()
        this.validated = false
      }
    },
    async discover(apiKey?: string | null): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      if (identityStore.discovering || !identityStore.baseUrl.trim()) {
        return
      }
      identityStore.discovering = true
      identityStore.error = null
      identityStore.rateLimited = false
      try {
        const res = await privateIdentityWork(identityStore, identityEpoch, () => (discoverCustom(identityStore.apiFormat, identityStore.baseUrl.trim(), apiKey ?? null)))
        identityStore.availableModels = [...res.models]
        identityStore.discoveredModels = [...res.models]
        identityStore.manualModel = res.manualModel
        identityStore.endpointPreview = res.endpointPreview
        identityStore.ensurePersistedVisible()
        identityStore.modelUnavailable = Boolean(
          !res.manualModel && identityStore.selectedModel && !res.models.includes(identityStore.selectedModel),
        )
        identityStore.failed = false
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

        // 连通性探测的判定:上游可达(含被限流)就不是「测试失败」。
        // 限流状态与错误信息保留,但绝不自动重试、绝不额外消耗配额。
        const probe = classifyProviderProbeFailure(err)
        identityStore.error = { code: probe.code, message: probe.message }
        identityStore.failed = !probe.reachable
        identityStore.rateLimited = probe.rateLimited
        // 认证/网络失败绝不降级为手动回退。
        identityStore.manualModel = false
      } finally {
        if (this.identityEpoch === identityEpoch) {
        identityStore.discovering = false
      
        }
      }
    
      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return }
        throw identityError
      }
    },
    async save(apiKey: string | null | undefined, displayName?: string | null): Promise<boolean> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      if (identityStore.saving) {
        return false
      }
      identityStore.saving = true
      identityStore.error = null
      try {
        const s = await privateIdentityWork(identityStore, identityEpoch, () => (saveCustomWithSource(identityStore.apiFormat, identityStore.baseUrl.trim(), apiKey,
          identityStore.selectedModel.trim(), identityStore.manualModel ? 'MANUAL' : 'DISCOVERED', displayName)))
        identityStore.configured = s.configured
        identityStore.apiFormat = s.apiFormat
        identityStore.baseUrl = s.baseUrl
        identityStore.savedBaseUrl = s.baseUrl
        identityStore.endpointPreview = s.endpointPreview
        identityStore.hasKey = s.hasKey
        identityStore.maskedKey = s.maskedKey
        identityStore.selectedModel = s.selectedModel
        identityStore.manualModel = s.manualModel
        identityStore.displayName = s.displayName
        identityStore.configRevision = s.configRevision
        identityStore.validated = s.validated
        identityStore.ensurePersistedVisible()
        identityStore.failed = false
        return true
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

        identityStore.error = displayError(err)
        identityStore.failed = true
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
    async validate(): Promise<boolean> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      if (identityStore.validating) {
        return false
      }
      identityStore.validating = true
      identityStore.error = null
        identityStore.rateLimited = false
      try {
        const s = await privateIdentityWork(identityStore, identityEpoch, () => (validateCustom()))
        identityStore.configured = s.configured
        identityStore.apiFormat = s.apiFormat
        identityStore.baseUrl = s.baseUrl
        identityStore.endpointPreview = s.endpointPreview
        identityStore.hasKey = s.hasKey
        identityStore.maskedKey = s.maskedKey
        identityStore.selectedModel = s.selectedModel
        identityStore.manualModel = s.manualModel
        identityStore.configRevision = s.configRevision
        identityStore.validated = s.validated
        identityStore.ensurePersistedVisible()
        identityStore.failed = false
        return s.validated
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

        identityStore.error = displayError(err)
        identityStore.failed = true
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
    clearError(): void {
      this.error = null
    },
  },
})
