// 文件名:openRouterStore.ts
// 用途:OpenRouter Provider 的 Pinia 状态仓:状态加载、密钥探测、模型列表刷新、
//       保存/校验,以及"可用列表 vs 展示列表"与已存选中项的可见性规则。

import { defineStore } from 'pinia'
import { identityScopedStore, privateIdentityWork, PrivateIdentityExpiredError } from '@/shared/state/privateIdentity'
import { toDisplayError } from '@/shared/http/displayError'
import { classifyProviderProbeFailure } from '@/shared/http/providerReachability'
import {
  getOpenRouterStatus,
  listOpenRouterModels,
  probeOpenRouter,
  saveOpenRouter,
  validateOpenRouter,
} from '@/features/model-settings/api/modelProviders'

/** 镜像后端的免费模型判定规则:openrouter/free 或 :free 后缀。 */
function isFreeModelId(id: string): boolean {
  return id === 'openrouter/free' || id.endsWith(':free')
}

export interface ProviderError {
  code: string
  message: string
}

const displayError: (err: unknown) => ProviderError = toDisplayError

export const useOpenRouterStore = defineStore('openRouter', {
  state: () => ({
    identityEpoch: 0,
    privateIdentity: null as string | null,
    configured: false,
    maskedKey: null as string | null,
    selectedModel: null as string | null,
    configRevision: 0,
    validated: false,
    // Provider 的完整目录(免费+付费)及免费子集;UI 默认展示完整列表,
    // 可通过"仅免费"开关过滤。
    allModels: [] as string[],
    freeModels: [] as string[],
    freeOnly: false,
    // 最近一次成功拉取得到的、Provider 真实可用的模型。
    // freeModels 是展示列表(可用列表 + 为可见性注入的已持久化值)。
    // 保存门槛必须用 availableModels,绝不能用展示列表,
    // 否则注入的不可用持久化值会被保存。
    availableModels: [] as string[],
    /** 首次状态加载中：此时列表为空只代表「还没到」，不代表「没有」。 */
    loading: false,
    probing: false,
    saving: false,
    validating: false,
    loadingModels: false,
    modelUnavailable: false,
    failed: false,
    /** 上游可达但当前被限流:连通性判定通过,限流状态本身必须保留。 */
    rateLimited: false,
    error: null as ProviderError | null,
  }),
  getters: {
    /** 展示列表:默认完整目录,开启"仅免费"后为免费子集。 */
    displayModels(state): string[] {
      return state.freeOnly
      && state.freeModels.length > 0 ? state.freeModels : state.allModels
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
    ensurePersistedVisible(): void {
      // 已持久化的选中项即使在模型(重新)拉取之前也必须保持可见。
      // 它以"已保存的值"身份被前置插入,绝不是悄悄换选了另一个模型。
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
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      identityStore.loading = true
      identityStore.error = null
      try {
        const s = await privateIdentityWork(identityStore, identityEpoch, () => (getOpenRouterStatus()))
        identityStore.configured = s.configured
        identityStore.maskedKey = s.maskedKey
        identityStore.selectedModel = s.selectedModel
        identityStore.configRevision = s.configRevision
        identityStore.validated = s.validated
        identityStore.ensurePersistedVisible()
        identityStore.failed = false
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
      if (identityStore.probing || !apiKey.trim()) {
        return []
      }
      identityStore.probing = true
      identityStore.error = null
      identityStore.rateLimited = false
      try {
        const res = await privateIdentityWork(identityStore, identityEpoch, () => (probeOpenRouter(apiKey.trim())))
        // 兼容旧后端：没有 allModels 时回退到 freeModels（只展示免费列表）。
        const all = Array.isArray(res.allModels) ? res.allModels : res.freeModels
        identityStore.availableModels = [...all]
        identityStore.allModels = [...all]
        identityStore.freeModels = [...(res.freeModels ?? [])]
        identityStore.selectedModel = null
        identityStore.modelUnavailable = false
        identityStore.failed = false
        return identityStore.allModels
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

        // 连通性探测:上游可达(含被限流)就不是「测试失败」;
        // 限流状态与错误信息保留,绝不自动重试。
        const probe = classifyProviderProbeFailure(err)
        identityStore.error = { code: probe.code, message: probe.message }
        identityStore.failed = !probe.reachable
        identityStore.rateLimited = probe.rateLimited
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
    async refreshModels(): Promise<void> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      if (identityStore.loadingModels) {
        return
      }
      identityStore.loadingModels = true
      identityStore.error = null
      identityStore.rateLimited = false
      try {
        const res = await privateIdentityWork(identityStore, identityEpoch, () => (listOpenRouterModels()))
        // 兼容旧后端：没有 allModels 时回退到 freeModels（只展示免费列表）。
        const all = Array.isArray(res.allModels) ? res.allModels : res.freeModels
        identityStore.availableModels = [...all]
        identityStore.allModels = [...all]
        identityStore.freeModels = [...(res.freeModels ?? [])]
        // 不可用判定发生在"为可见性前置插入持久化值"之前、对照真实在线列表,
        // 因此注入的过期选中项仍会被如实判定为不可用。
        const visible = identityStore.freeOnly && identityStore.freeModels.length > 0 ? identityStore.freeModels : identityStore.allModels
        identityStore.modelUnavailable = Boolean(
          identityStore.configured && identityStore.selectedModel && !visible.includes(identityStore.selectedModel),
        )
        identityStore.ensurePersistedVisible()
        identityStore.failed = false
      } catch (err) {
        if (this.identityEpoch !== identityEpoch) throw new PrivateIdentityExpiredError()

        // 模型目录刷新同样是一次可达性探测:429 表示上游可达但被限流,
        // 绝不是「不可达」,也绝不降级为手动模式。
        const probe = classifyProviderProbeFailure(err)
        identityStore.error = { code: probe.code, message: probe.message }
        identityStore.failed = !probe.reachable
        identityStore.rateLimited = probe.rateLimited
      } finally {
        if (this.identityEpoch === identityEpoch) {
        identityStore.loadingModels = false
      
        }
      }
    
      } catch (identityError) {
        if (identityError instanceof PrivateIdentityExpiredError) { return }
        throw identityError
      }
    },
    async save(apiKey: string | null, selectedModel: string): Promise<boolean> {
      const identityEpoch = this.identityEpoch
      const identityStore = identityScopedStore(this, identityEpoch)
      try {
      if (identityStore.saving) {
        return false
      }
      identityStore.saving = true
      identityStore.error = null
      try {
        const s = await privateIdentityWork(identityStore, identityEpoch, () => (saveOpenRouter(apiKey, selectedModel)))
        identityStore.configured = s.configured
        identityStore.maskedKey = s.maskedKey
        identityStore.selectedModel = s.selectedModel
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
      try {
        const s = await privateIdentityWork(identityStore, identityEpoch, () => (validateOpenRouter()))
        identityStore.configured = s.configured
        identityStore.maskedKey = s.maskedKey
        identityStore.selectedModel = s.selectedModel
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
