import { defineStore } from 'pinia'
import { ApiError, GENERIC_ERROR_MESSAGE } from '@/api/client'
import {
  getOpenCodeSettings,
  listOpenCodeModels,
  probeOpenCode,
  saveOpenCode,
  saveOpenCodeModel,
  validateOpenCode,
} from '@/api/modelSettings'
import type { OpenCodeSettingsStatus } from '@/api/types'

export interface ModelSettingsError {
  code: string
  message: string
}

function displayError(err: unknown): ModelSettingsError {
  if (err instanceof ApiError) {
    return { code: err.code, message: err.message }
  }
  return { code: 'UNKNOWN_ERROR', message: GENERIC_ERROR_MESSAGE }
}

export const useModelSettingsStore = defineStore('modelSettings', {
  state: () => ({
    status: null as OpenCodeSettingsStatus | null,
    // Every exposed model (free and paid) plus the free subset; the UI
    // defaults to the full list and can toggle "仅免费" to filter.
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
     * OpenCode has no persisted validated-revision column: save and
     * changeModel already prove reachability before their single upsert, so a
     * stored pair is validated by construction. This flag stays session-local
     * and only exists so the card can expose the same
     * 已配置但未验证 / 配置有效 / 测试失败 states as the OpenRouter card.
     */
    validated: false,
    error: null as ModelSettingsError | null,
  }),
  getters: {
    /** Display list: full catalog by default, free-only when toggled. */
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
        // A stored pair was proven reachable by the save/changeModel that
        // wrote it, so it starts out validated until a test says otherwise.
        this.validated = this.status.configured
        // 拉取之前先把已保存的模型放回可见列表，避免刷新期间闪空态。
        this.ensurePersistedVisible()
        // The guard keeps older isolated test doubles compatible while the
        // production module always exposes the saved-key endpoint.
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
        // A probe discovers choices but never picks one for the user.
        this.selectedModel = null
        this.changingCredential = true
        this.modelUnavailable = false
        return this.freeModels
      } catch (err) {
        // Keep the previous working status and previous successful model list
        // untouched when a candidate probe fails.
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
        // The server revalidated this exact pair before the upsert.
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
        // changeModel revalidates against the live catalog before writing.
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
     * Explicit reachability re-test on the stored pair. Never mutates the
     * saved configuration, so a failure leaves the working settings intact.
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
     * Enters credential-replacement mode.
     *
     * Deliberately does NOT wipe the catalog or the current selection: the
     * stored credential is still valid until a new pair is saved, so the model
     * list must stay usable exactly as it does on the OpenRouter card. Wiping
     * it here made 更换 API Key collapse the model select to a disabled
     * placeholder and hide 刷新模型 — the same shell behaving differently,
     * which is what "not the paradigm" looked like on screen.
     * A probe with the candidate key still replaces the list (see probe()).
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
