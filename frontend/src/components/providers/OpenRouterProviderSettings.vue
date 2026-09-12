<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import ApiErrorBanner from '@/components/ApiErrorBanner.vue'
import { productErrorMessage } from '@/api/errorCopy'
import { useOpenRouterStore } from '@/stores/openRouterStore'
import { useProviderSettingsStore } from '@/stores/providerSettingsStore'
import { openRouterState, stateLabel } from '@/presentation/providerPresentation'

const store = useOpenRouterStore()
const providers = useProviderSettingsStore()
const apiKey = ref('')

const isActive = computed(() => providers.activeProvider === 'OPENROUTER')
const state = computed(() => {
  if (store.validating || store.probing) {
    return 'validating' as const
  }
  return openRouterState(store.configured, store.validated, isActive.value, store.failed)
})
const stateLabelText = computed(() => stateLabel(state.value))
const canProbe = computed(() => apiKey.value.trim().length > 0 && !store.probing && !store.saving)
// Display list (freeModels) contains the injected persisted value for
// visibility. Save gating must use the true available list so an injected
// unavailable persisted value cannot be saved. Before any fetch,
// availableModels is empty and we fall back to the modelUnavailable flag
// (false after loadStatus), preserving the pre-fetch save behavior.
const isUnavailableSelected = computed(() => {
  if (store.selectedModel === null) {
    return false
  }
  if (store.availableModels.length > 0) {
    return !store.availableModels.includes(store.selectedModel)
  }
  return store.modelUnavailable
})
const showUnavailable = computed(() => {
  if (store.selectedModel === null) {
    return false
  }
  if (!store.freeModels.includes(store.selectedModel)) {
    return false
  }
  return isUnavailableSelected.value
})
const canSave = computed(() => store.selectedModel !== null && store.freeModels.includes(store.selectedModel)
  && !isUnavailableSelected.value
  && !store.saving && !store.probing && (apiKey.value.trim().length > 0 || store.configured))
const canValidate = computed(() => store.configured && !store.validating && !store.saving)
const canActivate = computed(() => store.configured && store.validated && !isActive.value && !providers.activating)
const safeErrorMessage = computed(() => productErrorMessage(store.error?.code ?? 'UNKNOWN_ERROR'))

async function probe(): Promise<void> {
  await store.probe(apiKey.value)
}

async function saveAndTest(): Promise<void> {
  if (!store.selectedModel) {
    return
  }
  const keyToSend = apiKey.value.trim().length > 0 ? apiKey.value.trim() : null
  const ok = await store.save(keyToSend, store.selectedModel)
  if (ok) {
    apiKey.value = ''
    await store.validate()
  }
}

async function activate(): Promise<void> {
  await providers.activate('OPENROUTER')
}

onMounted(() => {
  void store.loadStatus()
})
</script>

<template>
  <article class="settings-card" data-test="openrouter-card">
    <header class="settings-card__header">
      <div>
        <h3>OpenRouter</h3>
        <p class="settings-card__description">固定使用 Chat Completions 协议与官方模型列表，仅展示 free 模型。</p>
      </div>
      <span
        class="settings-status"
        :class="{
          'settings-status--active': state === 'active',
          'settings-status--configured': state === 'valid-inactive',
          'settings-status--warning': state === 'configured-unvalidated',
          'settings-status--error': state === 'invalid',
          'settings-status--empty': state === 'unconfigured' || state === 'validating',
        }"
        data-test="openrouter-state"
      >
        <span class="settings-status__dot" aria-hidden="true"></span>
        {{ stateLabelText }}
      </span>
    </header>

    <ApiErrorBanner
      v-if="store.error"
      class="settings-error"
      data-test="openrouter-error"
      :message="safeErrorMessage"
      :code="store.error.code"
      retry-label="重试"
      :retrying="store.probing || store.saving || store.validating || store.loadingModels"
      @retry="() => store.clearError()"
    />

    <section v-if="store.configured" class="settings-current-config" data-test="openrouter-current">
      <div class="settings-current-config__item">
        <span>API Key</span>
        <strong data-test="openrouter-masked">{{ store.maskedKey ?? '—' }}</strong>
      </div>
      <div class="settings-current-config__item">
        <span>当前模型</span>
        <strong class="ellipsis" :title="store.selectedModel ?? ''" data-test="openrouter-selected">{{ store.selectedModel ?? '—' }}</strong>
      </div>
      <div class="settings-current-config__item">
        <span>配置版本</span>
        <strong data-test="openrouter-revision">v{{ store.configRevision }}{{ store.validated ? ' · 已验证' : ' · 未验证' }}</strong>
      </div>
    </section>

    <div class="settings-form">
      <label class="settings-field" for="openrouter-api-key">
        <span class="settings-field__label">API Key</span>
        <span class="settings-field__hint">仅用于验证与保存，不会明文返回。已配置后可留空以复用已存密钥。</span>
        <input
          id="openrouter-api-key"
          v-model="apiKey"
          class="settings-control settings-key-input"
          type="password"
          autocomplete="off"
          data-test="openrouter-api-key"
          placeholder="sk-or-v1-…"
        />
      </label>
      <div class="settings-form__action-row">
        <button
          class="btn settings-action"
          type="button"
          data-test="openrouter-probe"
          :disabled="!canProbe"
          @click="probe"
        >
          {{ store.probing ? '正在验证…' : '验证并获取模型' }}
        </button>
        <button
          v-if="store.configured"
          class="btn settings-action"
          type="button"
          data-test="openrouter-refresh"
          :disabled="store.loadingModels"
          @click="() => store.refreshModels()"
        >
          {{ store.loadingModels ? '正在刷新…' : '刷新模型' }}
        </button>
      </div>
      <label class="settings-field" for="openrouter-model">
        <span class="settings-field__label">Model</span>
        <span class="settings-field__hint">仅展示 free 且具备文本与结构化输出能力的模型。</span>
        <select
          id="openrouter-model"
          v-model="store.selectedModel"
          class="settings-control settings-model-select"
          data-test="openrouter-model"
          :disabled="store.freeModels.length === 0 || store.saving"
        >
          <option :value="null" disabled>请选择 free 模型</option>
          <option v-for="model in store.freeModels" :key="model" :value="model">{{ model }}</option>
        </select>
        <span v-if="!store.probing && store.freeModels.length === 0" class="settings-field__empty">
          暂无可用 free 模型，请先验证 Key。
        </span>
        <span v-if="showUnavailable" class="settings-field__empty settings-field__warning" data-test="openrouter-unavailable">
          已保存的模型当前不可用，请重新验证后选择。
        </span>
      </label>
    </div>

    <footer class="settings-card__footer">
      <button
        class="btn btn-primary settings-action"
        type="button"
        data-test="openrouter-save-test"
        :disabled="!canSave"
        @click="saveAndTest"
      >
        {{ store.saving || store.validating ? '正在保存并测试…' : '保存并测试' }}
      </button>
      <button
        class="btn settings-action"
        type="button"
        data-test="openrouter-validate"
        :disabled="!canValidate"
        @click="() => store.validate()"
      >
        {{ store.validating ? '测试中…' : '重新测试' }}
      </button>
      <button
        class="btn settings-action"
        type="button"
        data-test="openrouter-activate"
        :disabled="!canActivate"
        :title="isActive ? '已是当前 Provider' : '需先通过兼容性测试才能激活'"
        @click="activate"
      >
        {{ isActive ? '当前使用' : (providers.activating ? '正在切换…' : '设为当前 Provider') }}
      </button>
    </footer>
  </article>
</template>

<style scoped>
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: block;
}
.settings-card__footer {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 18px;
}
.settings-status--active {
  color: var(--color-success);
  background: var(--color-success-soft);
  border-color: #c9e7d5;
}
.settings-status--warning {
  color: var(--color-warn);
  background: var(--color-warn-soft);
  border-color: #ecd9ae;
}
.settings-status--error {
  color: var(--color-danger);
  background: var(--color-danger-soft);
  border-color: #f0c4c0;
}
</style>
