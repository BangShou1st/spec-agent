// 文件名:openRouterStore.ts
// 用途:OpenRouter Provider 的 Pinia 状态仓:状态加载、密钥探测、模型列表刷新、
//       保存/校验,以及"可用列表 vs 展示列表"与已存选中项的可见性规则。

import { defineStore } from 'pinia'
import { toDisplayError } from '@/shared/http/displayError'
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
        // 不可用判定发生在"为可见性前置插入持久化值"之前、对照真实在线列表,
        // 因此注入的过期选中项仍会被如实判定为不可用。
        const visible = this.freeOnly && this.freeModels.length > 0 ? this.freeModels : this.allModels
        this.modelUnavailable = Boolean(
          this.configured && this.selectedModel && !visible.includes(this.selectedModel),
        )
        this.ensurePersistedVisible()
        this.failed = false
      } catch (err) {
        this.error = displayError(err)
        // 401/403/429/5xx 保持为硬错误,绝不降级为手动模式。
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
