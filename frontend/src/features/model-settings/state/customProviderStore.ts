// 文件名:customProviderStore.ts
// 用途:自定义 Provider 的 Pinia 状态仓:状态加载、模型发现、保存(含显示名称与
//       modelSource)、兼容性校验;维护"可用列表 vs 展示列表"与已存选中项的可见性规则。

import { defineStore } from 'pinia'
import { toDisplayError } from '@/shared/http/displayError'
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
    error: null as ProviderError | null,
  }),
  actions: {
    ensurePersistedVisible(): void {
      // 与 OpenRouter 相同的规则:(重新)拉取之前已持久化的选中项保持可见;
      // 手动模式由持久化的 modelSource 驱动。
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
        this.savedBaseUrl = s.baseUrl ?? null
        this.endpointPreview = s.endpointPreview
        this.hasKey = s.hasKey
        this.maskedKey = s.maskedKey
        this.selectedModel = s.selectedModel ?? ''
        this.manualModel = s.manualModel
        this.displayName = s.displayName
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
        // 更换格式会使发现与校验的展示结果失效。
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
        // 认证/网络失败绝不降级为手动回退。
        this.manualModel = false
      } finally {
        this.discovering = false
      }
    },
    async save(apiKey: string | null | undefined, displayName?: string | null): Promise<boolean> {
      if (this.saving) {
        return false
      }
      this.saving = true
      this.error = null
      try {
        const s = await saveCustomWithSource(this.apiFormat, this.baseUrl.trim(), apiKey,
          this.selectedModel.trim(), this.manualModel ? 'MANUAL' : 'DISCOVERED', displayName)
        this.configured = s.configured
        this.apiFormat = s.apiFormat
        this.baseUrl = s.baseUrl
        this.savedBaseUrl = s.baseUrl
        this.endpointPreview = s.endpointPreview
        this.hasKey = s.hasKey
        this.maskedKey = s.maskedKey
        this.selectedModel = s.selectedModel
        this.manualModel = s.manualModel
        this.displayName = s.displayName
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
