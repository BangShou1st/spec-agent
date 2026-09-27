// 文件名:modelSettingsStore.ts
// 用途:OpenCode Zen 模型设置的 Pinia 状态仓:设置状态加载、密钥探测、模型列表刷新、
//       保存(密钥+模型)/改模型/校验,以及"已存选中项在拉取前保持可见"等展示规则。

import { defineStore } from 'pinia'
import { toDisplayError } from '@/shared/http/displayError'
import {
  getOpenCodeSettings,
  listOpenCodeModels,
  probeOpenCode,
  saveOpenCode,
  saveOpenCodeModel,
  validateOpenCode,
} from '@/features/model-settings/api/modelSettings'
import type { OpenCodeSettingsStatus } from '@/shared/contracts/types'

export interface ModelSettingsError {
  code: string
  message: string
}

const displayError: (err: unknown) => ModelSettingsError = toDisplayError

export const useModelSettingsStore = defineStore('modelSettings', {
  state: () => ({
    status: null as OpenCodeSettingsStatus | null,
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
     * OpenCode 没有持久化的"已校验版本"列:save 与 changeModel 在唯一一次
     * upsert 之前就已证明可达性,因此已存的(密钥,模型)对天然视为已校验。
     * 此标志只在会话内有效,存在的原因仅仅是让卡片能呈现与 OpenRouter 卡
     * 相同的 已配置但未验证 / 配置有效 / 测试失败 三种状态。
     */
    validated: false,
    error: null as ModelSettingsError | null,
  }),
  getters: {
    /** 展示列表:默认完整目录,开启"仅免费"后为免费子集。 */
    displayModels(state): string[] {
      return state.freeOnly
      && state.freeModels.length > 0 ? state.freeModels : state.allModels
    },
  },
  actions: {
    /**
     * 与 OpenRouter 卡同一条规则：已保存的选中模型在（重新）拉取列表之前必须
     * 保持可见。否则列表为空的那段时间里，卡片会闪出「暂无可用模型」并把选择框
     * 置灰 —— 这正是 OpenCode 卡与 OpenRouter 卡看起来行为不一致的原因。
     */
    ensurePersistedVisible(): void {
      const persisted = this.status?.selectedModel
      if (!persisted) {
        return
      }
      if (!this.allModels.includes(persisted)) {
        this.allModels = [persisted, ...this.allModels]
      }
      // 后端 isFreeModel 的规则就是 -free 后缀，前端按同一规则镜像，
      // 否则打开「仅免费」时会把这个模型从可见列表里漏掉。
      if (persisted.endsWith('-free') && !this.freeModels.includes(persisted)) {
        this.freeModels = [persisted, ...this.freeModels]
      }
    },
    async loadStatus(): Promise<void> {
      this.loading = true
      this.error = null
      try {
        this.status = await getOpenCodeSettings()
        this.selectedModel = this.status.selectedModel
        this.allModels = []
        this.freeModels = []
        this.modelUnavailable = false
        // 已存的(密钥,模型)对在写入它的 save/changeModel 中已被证明可达,
        // 因此初始视为已校验,直到某次测试给出相反结论。
        this.validated = this.status.configured
        // 拉取之前先把已保存的模型放回可见列表，避免刷新期间闪空态。
        this.ensurePersistedVisible()
        // 该守护保持旧的隔离测试替身兼容;生产模块始终暴露已存密钥端点。
        if (this.status.configured && typeof listOpenCodeModels === 'function') {
          await this.refreshModels()
        }
      } catch (err) {
        this.error = displayError(err)
      } finally {
        this.loading = false
      }
    },

    async probe(apiKey: string): Promise<string[]> {
      if (this.probing || !apiKey.trim()) return []
      this.probing = true
      this.error = null
      try {
        const result = await probeOpenCode(apiKey.trim())
        // 兼容旧后端：没有 allModels 时回退到 freeModels（只展示免费列表）。
        const all = Array.isArray(result.allModels) ? result.allModels : result.freeModels
        this.allModels = [...all]
        this.freeModels = [...(result.freeModels ?? [])]
        // 探测只负责发现可选项,绝不替用户做选择。
        this.selectedModel = null
        this.changingCredential = true
        this.modelUnavailable = false
        return this.freeModels
      } catch (err) {
        // 候选密钥探测失败时,保持之前的工作状态与成功的模型列表不动。
        this.error = displayError(err)
        return []
      } finally {
        this.probing = false
      }
    },

    async save(apiKey: string, selectedModel: string): Promise<boolean> {
      if (this.saving || !apiKey.trim() || !selectedModel) return false
      this.saving = true
      this.error = null
      try {
        this.status = await saveOpenCode(apiKey.trim(), selectedModel)
        this.allModels = []
        this.freeModels = []
        this.selectedModel = this.status.selectedModel
        this.changingCredential = false
        this.modelUnavailable = false
        // 服务端在 upsert 之前已对这一对(密钥,模型)重新校验过。
        this.validated = true
        await this.refreshModels()
        return true
      } catch (err) {
        this.error = displayError(err)
        return false
      } finally {
        this.saving = false
      }
    },

    async refreshModels(): Promise<boolean> {
      if (this.loadingModels || typeof listOpenCodeModels !== 'function') return false
      this.loadingModels = true
      this.error = null
      try {
        const result = await listOpenCodeModels()
        // 兼容旧后端：没有 allModels 时回退到 freeModels（只展示免费列表）。
        const all = Array.isArray(result.allModels) ? result.allModels : result.freeModels
        this.allModels = [...all]
        this.freeModels = [...(result.freeModels ?? [])]
        const visible = this.freeOnly && this.freeModels.length > 0 ? this.freeModels : this.allModels
        this.modelUnavailable = Boolean(
          this.status?.selectedModel && !visible.includes(this.status.selectedModel),
        )
        this.selectedModel = this.status?.selectedModel ?? null
        // 与新列表合并后再放回已保存项：不可用判定用的是「真实列表」（上一行），
        // 可见性注入发生在判定之后，所以注入不会掩盖真正的不可用。
        this.ensurePersistedVisible()
        return true
      } catch (err) {
        this.error = displayError(err)
        return false
      } finally {
        this.loadingModels = false
      }
    },

    async saveModel(selectedModel: string): Promise<boolean> {
      if (this.saving || !this.status?.configured || !selectedModel) return false
      this.saving = true
      this.error = null
      try {
        this.status = await saveOpenCodeModel(selectedModel)
        this.selectedModel = this.status.selectedModel
        this.modelUnavailable = false
        // changeModel 在写入前会对照实时目录重新校验。
        this.validated = true
        return true
      } catch (err) {
        this.error = displayError(err)
        return false
      } finally {
        this.saving = false
      }
    },

    /**
     * 对已存的(密钥,模型)对做显式的可达性重测。
     * 绝不修改已保存的配置,因此失败时工作中的设置原样保留。
     */
    async validate(): Promise<boolean> {
      if (this.validating || typeof validateOpenCode !== 'function') {
        return false
      }
      this.validating = true
      this.error = null
      try {
        this.status = await validateOpenCode()
        this.selectedModel = this.status.selectedModel
        this.validated = true
        return true
      } catch (err) {
        this.error = displayError(err)
        this.validated = false
        return false
      } finally {
        this.validating = false
      }
    },

    /**
     * 进入更换凭证模式。
     *
     * 刻意**不**清空模型目录和当前选择:已存凭证在新的一对(密钥,模型)保存之前
     * 仍然有效,模型列表必须保持可用,与 OpenRouter 卡完全一致。
     * 此前在这里清空列表,导致点 更换 API Key 后模型下拉塌缩成禁用的占位项、
     * 刷新模型 按钮消失——同一个外壳表现不一致,这就是"不像范式"在界面上的样子。
     * 使用候选密钥的探测仍会替换列表(见 probe())。
     */
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
