<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import ApiErrorBanner from '@/components/ApiErrorBanner.vue'
import { productErrorMessage } from '@/api/errorCopy'
import { useCustomProviderStore } from '@/stores/customProviderStore'
import { useProviderSettingsStore } from '@/stores/providerSettingsStore'
import { CUSTOM_FORMAT_OPTIONS, endpointPreview, stateLabel } from '@/presentation/providerPresentation'
import type { CustomApiFormat } from '@/api/modelProviders'

const store = useCustomProviderStore()
const providers = useProviderSettingsStore()
const apiKey = ref('')
const keyTouched = ref(false)

const isActive = computed(() => providers.activeProvider === 'CUSTOM')
const preview = computed(() => endpointPreview(store.baseUrl, store.apiFormat))
const state = computed(() => {
  if (store.validating || store.discovering) {
    return 'validating' as const
  }
  if (isActive.value && store.validated) {
    return 'active' as const
  }
  if (store.failed) {
    return 'invalid' as const
  }
  if (!store.configured) {
    return 'unconfigured' as const
  }
  if (store.validated) {
    return 'valid-inactive' as const
  }
  return 'configured-unvalidated' as const
})
const stateText = computed(() => stateLabel(state.value))
const canDiscover = computed(() => store.baseUrl.trim().length > 0 && !store.discovering && !store.saving)
// Discovered display list contains the injected persisted value for
// visibility. Save gating must use the true available list in discovered
// mode; manual mode is unaffected.
const isUnavailableSelected = computed(() => {
  if (store.manualModel) {
    return false
  }
  const selected = store.selectedModel.trim()
  if (!selected) {
    return false
  }
  if (store.availableModels.length > 0) {
    return !store.availableModels.includes(selected)
  }
  return store.modelUnavailable
})
const showUnavailable = computed(() => {
  if (store.manualModel) {
    return false
  }
  const selected = store.selectedModel.trim()
  if (!selected || !store.discoveredModels.includes(selected)) {
    return false
  }
  return isUnavailableSelected.value
})
const canSaveTest = computed(() => store.baseUrl.trim().length > 0
  && store.selectedModel.trim().length > 0 && !isUnavailableSelected.value
  && !store.saving && !store.validating)
const canActivate = computed(() => store.configured && store.validated && !isActive.value && !providers.activating)
const safeErrorMessage = computed(() => productErrorMessage(store.error?.code ?? 'UNKNOWN_ERROR'))

function onFormatChange(event: Event): void {
  const value = (event.target as HTMLSelectElement).value as CustomApiFormat
  store.setFormat(value)
}

async function discover(): Promise<void> {
  // Pass the draft key only when the user typed one; otherwise discover with stored/no key.
  const draft = keyTouched.value ? apiKey.value : null
  await store.discover(draft)
}

async function saveAndTest(): Promise<void> {
  // undefined retains the stored key; empty clears; non-empty sets new.
  const payload = !keyTouched.value ? undefined : (apiKey.value === '' ? '' : apiKey.value.trim())
  const ok = await store.save(payload as string | null | undefined)
  if (ok) {
    apiKey.value = ''
    keyTouched.value = false
    await store.validate()
  }
}

async function activate(): Promise<void> {
  await providers.activate('CUSTOM')
}

watch(() => store.apiFormat, () => {
  // Format switch clears stale discovery presentation immediately.
})

onMounted(() => {
  void store.loadStatus()
})
</script>

<template>
  <article class="settings-card" data-test="custom-card">
    <header class="settings-card__header">
      <div>
        <h3>Custom</h3>
        <p class="settings-card__description">单个自定义兼容网关，协议由你明确选择，不自动探测、不自动回退。</p>
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
        data-test="custom-state"
      >
        <span class="settings-status__dot" aria-hidden="true"></span>
        {{ stateText }}
      </span>
    </header>

    <ApiErrorBanner
      v-if="store.error"
      class="settings-error"
      data-test="custom-error"
      :message="safeErrorMessage"
      :code="store.error.code"
      retry-label="重试"
      :retrying="store.discovering || store.saving || store.validating"
      @retry="() => store.clearError()"
    />

    <section v-if="store.configured" class="settings-current-config" data-test="custom-current">
      <div class="settings-current-config__item">
        <span>API Format</span>
        <strong data-test="custom-current-format">{{ store.apiFormat }}</strong>
      </div>
      <div class="settings-current-config__item">
        <span>Base URL</span>
        <strong class="ellipsis" :title="store.baseUrl" data-test="custom-current-base">{{ store.baseUrl }}</strong>
      </div>
      <div class="settings-current-config__item">
        <span>Model</span>
        <strong class="ellipsis" :title="store.selectedModel" data-test="custom-current-model">{{ store.selectedModel }}</strong>
      </div>
      <div class="settings-current-config__item">
        <span>版本</span>
        <strong data-test="custom-revision">v{{ store.configRevision }}{{ store.validated ? ' · 已验证' : ' · 未验证' }}</strong>
      </div>
    </section>

    <div class="settings-form">
      <label class="settings-field" for="custom-format">
        <span class="settings-field__label">API Format</span>
        <select
          id="custom-format"
          :value="store.apiFormat"
          class="settings-control settings-model-select"
          data-test="custom-format"
          :disabled="store.saving || store.validating"
          @change="onFormatChange"
        >
          <option
            v-for="opt in CUSTOM_FORMAT_OPTIONS"
            :key="opt.value"
            :value="opt.value"
          >
            {{ opt.label }}
          </option>
        </select>
      </label>

      <label class="settings-field" for="custom-base-url">
        <span class="settings-field__label">Base URL</span>
        <span class="settings-field__hint">API Base URL，通常以 /v1 结尾。</span>
        <input
          id="custom-base-url"
          v-model="store.baseUrl"
          class="settings-control settings-key-input"
          type="text"
          autocomplete="off"
          spellcheck="false"
          data-test="custom-base-url"
          placeholder="https://gateway.example/v1"
        />
        <span class="settings-field__preview" data-test="custom-endpoint-preview">
          请求地址：{{ preview ?? '—' }}
        </span>
      </label>

      <label class="settings-field" for="custom-api-key">
        <span class="settings-field__label">API Key（可选）</span>
        <span class="settings-field__hint">本地或无鉴权服务可留空。已配置后留空且未改动则复用已存密钥。</span>
        <input
          id="custom-api-key"
          v-model="apiKey"
          class="settings-control settings-key-input"
          type="password"
          autocomplete="off"
          data-test="custom-api-key"
          placeholder="留空表示无鉴权"
          @input="keyTouched = true"
        />
      </label>

      <div class="settings-form__action-row">
        <button
          class="btn settings-action"
          type="button"
          data-test="custom-discover"
          :disabled="!canDiscover"
          @click="discover"
        >
          {{ store.discovering ? '正在获取…' : '获取模型' }}
        </button>
      </div>

      <label v-if="!store.manualModel" class="settings-field" for="custom-model">
        <span class="settings-field__label">Model</span>
        <select
          id="custom-model"
          v-model="store.selectedModel"
          class="settings-control settings-model-select"
          data-test="custom-model"
          :disabled="store.discoveredModels.length === 0 || store.saving"
        >
          <option value="" disabled>请选择模型</option>
          <option v-for="model in store.discoveredModels" :key="model" :value="model">{{ model }}</option>
        </select>
        <span v-if="store.discoveredModels.length === 0" class="settings-field__empty">
          先点击“获取模型”。若网关不支持 /models，将切换为手动输入。
        </span>
        <span v-if="showUnavailable" class="settings-field__empty settings-field__warning" data-test="custom-unavailable">
          已保存的模型当前不可用，请重新获取后选择。
        </span>
      </label>
      <label v-else class="settings-field" for="custom-model-id">
        <span class="settings-field__label">Model ID</span>
        <span class="settings-field__hint">网关不支持模型列表，请手动填写，仍需通过兼容性测试。</span>
        <input
          id="custom-model-id"
          v-model="store.selectedModel"
          class="settings-control settings-key-input"
          type="text"
          autocomplete="off"
          spellcheck="false"
          data-test="custom-model-id"
          placeholder="输入模型 ID"
        />
      </label>
    </div>

    <footer class="settings-card__footer">
      <button
        class="btn btn-primary settings-action"
        type="button"
        data-test="custom-save-test"
        :disabled="!canSaveTest"
        @click="saveAndTest"
      >
        {{ store.saving || store.validating ? '正在保存并测试…' : '保存并测试' }}
      </button>
      <button
        class="btn settings-action"
        type="button"
        data-test="custom-validate"
        :disabled="!store.configured || store.validating"
        @click="() => store.validate()"
      >
        {{ store.validating ? '测试中…' : '重新测试' }}
      </button>
      <button
        class="btn settings-action"
        type="button"
        data-test="custom-activate"
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
.settings-field__preview {
  margin-top: 6px;
  color: var(--color-text-secondary);
  font-size: 12px;
  word-break: break-all;
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
