<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import ApiErrorBanner from '@/components/ApiErrorBanner.vue'
import { productErrorMessage, requiresModelSettings } from '@/api/errorCopy'
import { useModelSettingsStore } from '@/stores/modelSettingsStore'

type RetryAction = 'load' | 'models' | 'probe' | 'save' | 'save-model' | null

const store = useModelSettingsStore()
const apiKey = ref('')
const probed = ref(false)
const retryAction = ref<RetryAction>(null)

const canProbe = computed(() => apiKey.value.trim().length > 0 && !store.probing && !store.saving)
const canSave = computed(() => probed.value && apiKey.value.trim().length > 0
  && store.selectedModel !== null && !store.saving && !store.probing)
const canSaveModel = computed(() => store.status?.configured === true
  && !store.changingCredential && store.selectedModel !== null
  && store.freeModels.includes(store.selectedModel)
  && !store.saving && !store.loadingModels)
const canReset = computed(() => apiKey.value.length > 0 || probed.value
  || (store.status?.configured === true && store.changingCredential))
const safeErrorMessage = computed(() => productErrorMessage(store.error?.code ?? 'UNKNOWN_ERROR'))
const showCredentialForm = computed(() => !store.status?.configured || store.changingCredential)
const authenticationFailed = computed(() => store.error?.code.toUpperCase().includes('AUTHENTICATION') ?? false)
const settingsActionRequired = computed(() => store.error !== null
  && requiresModelSettings(store.error.code))

async function loadStatus(): Promise<void> {
  retryAction.value = 'load'
  await store.loadStatus()
  if (!store.error) retryAction.value = null
}

async function refreshModels(): Promise<void> {
  retryAction.value = 'models'
  await store.refreshModels()
  if (!store.error) retryAction.value = null
}

async function probe(): Promise<void> {
  retryAction.value = 'probe'
  const models = await store.probe(apiKey.value)
  probed.value = models.length > 0
  if (!store.error) retryAction.value = null
}

async function save(): Promise<void> {
  if (!store.selectedModel) return
  retryAction.value = 'save'
  const saved = await store.save(apiKey.value, store.selectedModel)
  if (saved) {
    apiKey.value = ''
    probed.value = false
    retryAction.value = null
  }
}

async function saveModel(): Promise<void> {
  if (!store.selectedModel) return
  retryAction.value = 'save-model'
  const saved = await store.saveModel(store.selectedModel)
  if (saved) retryAction.value = null
}

async function resetDraft(): Promise<void> {
  apiKey.value = ''
  probed.value = false
  retryAction.value = null
  if (store.status?.configured && store.changingCredential) {
    store.cancelCredentialChange()
    await store.refreshModels()
  } else {
    store.resetProbe()
  }
}

async function retryLastAction(): Promise<void> {
  if (authenticationFailed.value || store.error?.code.toUpperCase().includes('NOT_CONFIGURED')) {
    store.beginCredentialChange()
    retryAction.value = null
    return
  }
  if (settingsActionRequired.value) {
    await refreshModels()
    return
  }
  if (retryAction.value === 'load') {
    await loadStatus()
  } else if (retryAction.value === 'models') {
    await refreshModels()
  } else if (retryAction.value === 'probe') {
    await probe()
  } else if (retryAction.value === 'save') {
    await save()
  } else if (retryAction.value === 'save-model') {
    await saveModel()
  } else {
    store.clearError()
  }
}

onMounted(() => {
  void loadStatus()
})
</script>

<template>
  <section class="settings-page" data-test="settings-page">
    <header class="settings-page__heading">
      <p class="eyebrow">设置</p>
      <h1>模型设置</h1>
      <p class="settings-page__subtitle">管理 Spec Agent 使用的模型和 OpenCode 连接。</p>
    </header>

    <ApiErrorBanner
      v-if="store.error"
      class="settings-error"
      data-test="settings-error"
      :message="safeErrorMessage"
      :code="store.error.code"
      :retry-label="settingsActionRequired ? '前往模型设置' : '重试'"
      :retrying="store.loading || store.probing || store.saving || store.loadingModels"
      @retry="retryLastAction"
    />

    <article class="settings-card" data-test="opencode-card">
      <header class="settings-card__header">
        <div>
          <p class="settings-card__eyebrow">模型连接</p>
          <h2>OpenCode</h2>
        </div>
        <span
          class="settings-status"
          :class="store.status?.configured ? 'settings-status--configured' : 'settings-status--empty'"
          data-test="configuration-status"
        >
          <span class="settings-status__dot" aria-hidden="true"></span>
          {{ store.status?.configured ? '已配置' : '尚未配置' }}
        </span>
      </header>

      <p class="settings-card__description">用于问题生成、回答理解和 Spec 生成。</p>

      <section v-if="store.status?.configured" class="settings-current-config" data-test="current-config">
        <div class="settings-current-config__item">
          <span>API Key</span>
          <strong data-test="masked-key">{{ store.status.maskedKey }}</strong>
        </div>
        <div class="settings-current-config__item">
          <span>当前模型</span>
          <strong>{{ store.status.selectedModel }}</strong>
        </div>
        <button
          class="btn settings-action"
          type="button"
          data-test="change-api-key"
          :disabled="store.probing || store.saving"
          @click="store.beginCredentialChange()"
        >
          更换 API Key
        </button>
      </section>

      <div v-if="showCredentialForm" class="settings-form settings-form--credential">
        <label class="settings-field" for="opencode-api-key">
          <span class="settings-field__label">新 API Key</span>
          <span class="settings-field__hint">密钥只用于验证和保存，不会显示完整内容。</span>
          <input
            id="opencode-api-key"
            v-model="apiKey"
            class="settings-control settings-key-input"
            type="password"
            autocomplete="off"
            data-test="opencode-api-key"
            placeholder="输入你的 API Key"
          />
        </label>

        <div class="settings-form__action-row">
          <button
            class="btn settings-action settings-action--probe"
            type="button"
            data-test="probe-opencode"
            :disabled="!canProbe"
            @click="probe"
          >
            {{ store.probing ? '正在验证…' : '验证并获取模型' }}
          </button>
        </div>

        <label class="settings-field" for="opencode-model">
          <span class="settings-field__label">可用模型</span>
          <span class="settings-field__hint">验证后请选择一个当前可用的 free model。</span>
          <select
            id="opencode-model"
            v-model="store.selectedModel"
            class="settings-control settings-model-select"
            data-test="opencode-model"
            :disabled="!probed || store.freeModels.length === 0 || store.saving"
          >
            <option :value="null" disabled>请选择一个 free model</option>
            <option v-for="model in store.freeModels" :key="model" :value="model">{{ model }}</option>
          </select>
          <span v-if="probed && store.freeModels.length === 0" class="settings-field__empty">
            当前没有可用的 free model。
          </span>
        </label>
      </div>
      <section v-if="store.status?.configured && !store.changingCredential" class="settings-models" data-test="saved-key-models">
        <label class="settings-field" for="opencode-model">
          <span class="settings-field__label">可用模型</span>
          <span class="settings-field__hint">使用当前已保存的 API Key 获取，不需要重新输入密钥。</span>
          <select
            id="opencode-model"
            v-model="store.selectedModel"
            class="settings-control settings-model-select"
            data-test="opencode-model"
            :disabled="store.loadingModels || store.saving || store.freeModels.length === 0"
          >
            <option :value="null" disabled>请选择一个 free model</option>
            <option v-for="model in store.freeModels" :key="model" :value="model">{{ model }}</option>
          </select>
          <span v-if="store.modelUnavailable" class="settings-field__empty settings-field__warning">
            当前模型已不可用，请重新选择。
          </span>
        </label>
        <div class="settings-form__action-row">
          <button
            class="btn settings-action"
            type="button"
            data-test="refresh-models"
            :disabled="store.loadingModels || store.saving"
            @click="refreshModels"
          >
            {{ store.loadingModels ? '正在刷新…' : '刷新可用模型' }}
          </button>
          <button
            class="btn btn-primary settings-action"
            type="button"
            data-test="save-model"
            :disabled="!canSaveModel"
            @click="saveModel"
          >
            {{ store.saving ? '正在保存…' : '保存模型' }}
          </button>
        </div>
      </section>

      <footer class="settings-card__footer">
        <button
          v-if="canReset"
          class="btn settings-action"
          type="button"
          data-test="reset-opencode"
          :disabled="store.probing || store.saving"
          @click="resetDraft"
        >
          {{ store.status?.configured && store.changingCredential ? '取消更换' : '取消/重置' }}
        </button>
        <button
          v-if="showCredentialForm"
          class="btn btn-primary settings-action settings-action--save"
          type="button"
          data-test="save-opencode"
          :disabled="!canSave"
          @click="save"
        >
          {{ store.saving ? '正在保存…' : '保存凭证' }}
        </button>
      </footer>
    </article>
  </section>
</template>

<style scoped>
.settings-page {
  width: 100%;
  max-width: 840px;
  margin: 0 auto;
  padding: 32px 0 64px;
}

.settings-page__heading {
  margin-bottom: 24px;
}

.settings-page__heading .eyebrow {
  margin: 0 0 6px;
  color: var(--color-text-muted);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.08em;
}

.settings-page__heading h1 {
  margin: 0;
  font-size: 30px;
  line-height: 1.2;
  letter-spacing: -0.02em;
}

.settings-page__subtitle {
  margin: 8px 0 0;
  color: var(--color-text-secondary);
  font-size: 14px;
}

.settings-card {
  width: 100%;
  padding: 28px 32px 24px;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 12px;
  box-shadow: 0 8px 24px rgb(31 36 48 / 7%);
}

.settings-card__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
}

.settings-card__eyebrow {
  margin: 0 0 4px;
  color: var(--color-text-muted);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.settings-card h2 {
  margin: 0;
  font-size: 21px;
  line-height: 1.25;
}

.settings-status {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 5px 10px;
  border: 1px solid transparent;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.settings-status--configured {
  color: var(--color-success);
  background: var(--color-success-soft);
  border-color: #c9e7d5;
}

.settings-status--empty {
  color: var(--color-text-secondary);
  background: var(--color-subdued);
  border-color: var(--color-border);
}

.settings-status__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
}

.settings-card__description {
  margin: 12px 0 24px;
  color: var(--color-text-secondary);
}

.settings-current-config {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 24px;
  margin-bottom: 26px;
  padding: 14px 16px;
  background: #fafbfc;
  border: 1px solid var(--color-border);
  border-radius: 8px;
}

.settings-current-config__item {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.settings-current-config__item span {
  color: var(--color-text-muted);
  font-size: 12px;
}

.settings-current-config__item strong {
  overflow: hidden;
  color: var(--color-text);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.settings-form {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.settings-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.settings-field__label {
  color: var(--color-text);
  font-size: 13px;
  font-weight: 600;
}

.settings-field__hint,
.settings-field__empty {
  color: var(--color-text-muted);
  font-size: 12px;
}

.settings-control {
  width: 100%;
  min-height: 0;
  height: 44px;
  padding: 0 12px;
  border: 1px solid var(--color-border);
  border-radius: 7px;
  background: var(--color-surface);
  color: var(--color-text);
  transition: border-color 120ms ease, box-shadow 120ms ease;
}

.settings-control:focus {
  outline: none;
  border-color: var(--color-accent);
  box-shadow: 0 0 0 3px var(--color-accent-soft);
}

.settings-control:disabled {
  color: var(--color-text-muted);
  background: var(--color-subdued);
}

.settings-form__action-row {
  display: flex;
  align-items: center;
}

.settings-action {
  min-width: 132px;
  min-height: 40px;
  padding: 8px 16px;
}

.settings-action--probe {
  min-width: 148px;
}

.settings-card__footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 28px;
  padding-top: 20px;
  border-top: 1px solid var(--color-border);
}

.settings-error {
  margin-bottom: 16px;
}

@media (max-width: 700px) {
  .settings-page {
    padding: 24px 0 40px;
  }

  .settings-card {
    padding: 22px 18px 18px;
  }

  .settings-current-config {
    grid-template-columns: 1fr;
  }

  .settings-card__footer,
  .settings-form__action-row {
    align-items: stretch;
    flex-direction: column;
  }

  .settings-action {
    width: 100%;
  }
}
</style>
