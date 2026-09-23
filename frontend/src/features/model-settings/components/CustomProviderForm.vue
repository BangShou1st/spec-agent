<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { productErrorMessage } from '@/shared/http/errorCopy'
import { useCustomProviderStore } from '@/features/model-settings/state/customProviderStore'
import { useProviderSettingsStore } from '@/features/model-settings/state/providerSettingsStore'
import { CUSTOM_FORMAT_OPTIONS, endpointPreview } from '@/features/model-settings/presentation/providerPresentation'
import ApiErrorBanner from '@/shared/ui/ApiErrorBanner.vue'
import type { CustomApiFormat } from '@/features/model-settings/api/modelProviders'

/**
 * The one editing surface for a user-defined provider, shared by the create
 * flow and the edit flow.
 *
 * Deliberately flat: no card header, no section divider, no status pill. The
 * field sequence runs straight through — 显示名称 → API Format → Base URL →
 * API Key → 获取模型 → Model → actions — so the same fields are never split
 * into two visually separate blocks just because a shell wraps them.
 */
const props = withDefaults(defineProps<{
  /** 'create' starts empty; 'edit' seeds every field from the stored config. */
  mode?: 'create' | 'edit'
  /** Dialog-owned id prefix so nested fields keep unique DOM ids. */
  idPrefix?: string
}>(), {
  mode: 'create',
  idPrefix: 'custom',
})

const emit = defineEmits<{ (e: 'saved', ok: boolean): void }>()

const store = useCustomProviderStore()
const providers = useProviderSettingsStore()

const name = ref('')
const apiKey = ref('')
const keyTouched = ref(false)

const isEdit = computed(() => props.mode === 'edit')
const preview = computed(() => endpointPreview(store.baseUrl, store.apiFormat))
const safeErrorMessage = computed(() => productErrorMessage(store.error?.code ?? 'UNKNOWN_ERROR'))
const isActive = computed(() => providers.activeProvider === 'CUSTOM')

const canDiscover = computed(() => store.baseUrl.trim().length > 0 && !store.discovering && !store.saving)
const canSaveTest = computed(() => name.value.trim().length > 0
  && store.baseUrl.trim().length > 0
  && store.selectedModel.trim().length > 0
  && !isUnavailableSelected.value
  && !store.saving && !store.validating)
const canActivate = computed(() => store.configured && store.validated
  && !isActive.value && !providers.activating)

/**
 * The discovered display list carries the persisted value for visibility; the
 * save gate must use the true available list so an unavailable saved model can
 * never be written back.
 */
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

function seedName(): void {
  name.value = isEdit.value ? (store.displayName?.trim() ?? '') : ''
}

function onFormatChange(event: Event): void {
  store.setFormat((event.target as HTMLSelectElement).value as CustomApiFormat)
}

async function discover(): Promise<void> {
  // Only a key the user actually typed is a candidate; otherwise reuse stored.
  const draft = keyTouched.value ? apiKey.value : null
  await store.discover(draft)
}

async function saveAndTest(): Promise<void> {
  // undefined retains the stored key, empty clears it, non-empty replaces it.
  const payload = !keyTouched.value ? undefined : (apiKey.value === '' ? '' : apiKey.value.trim())
  const ok = await store.save(payload as string | null | undefined, name.value.trim())
  if (ok) {
    apiKey.value = ''
    keyTouched.value = false
    await store.validate()
    emit('saved', true)
  }
}

async function activate(): Promise<void> {
  await providers.activate('CUSTOM')
}

watch(() => store.displayName, () => {
  if (!name.value) {
    seedName()
  }
})

onMounted(() => {
  void store.loadStatus().then(seedName)
})
</script>

<template>
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

  <div class="settings-form">
    <label class="settings-field" :for="`${idPrefix}-display-name`">
      <span class="settings-field__label">显示名称</span>
      <span class="settings-field__hint">显示在模型页与「当前使用」胶囊上，例如「本地 Ollama」</span>
      <input
        :id="`${idPrefix}-display-name`"
        v-model="name"
        class="settings-control"
        type="text"
        autocomplete="off"
        maxlength="64"
        placeholder="例如 本地 Ollama"
        data-test="custom-display-name"
      />
    </label>

    <label class="settings-field" :for="`${idPrefix}-format`">
      <span class="settings-field__label">API Format</span>
      <span class="settings-field__hint">选择与网关一致的协议；不自动探测，也不自动回退到其他格式</span>
      <select
        :id="`${idPrefix}-format`"
        class="settings-control settings-model-select"
        data-test="custom-format"
        :value="store.apiFormat"
        :disabled="store.saving || store.validating"
        @change="onFormatChange"
      >
        <option v-for="opt in CUSTOM_FORMAT_OPTIONS" :key="opt.value" :value="opt.value">
          {{ opt.label }}
        </option>
      </select>
    </label>

    <label class="settings-field" :for="`${idPrefix}-base-url`">
      <span class="settings-field__label">Base URL</span>
      <span class="settings-field__hint">API Base URL，通常以 /v1 结尾</span>
      <input
        :id="`${idPrefix}-base-url`"
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

    <label class="settings-field" :for="`${idPrefix}-api-key`">
      <span class="settings-field__label">API Key（可选）</span>
      <span class="settings-field__hint">本地或无鉴权服务可留空。已配置后留空且未改动则复用已存密钥</span>
      <input
        :id="`${idPrefix}-api-key`"
        v-model="apiKey"
        class="settings-control settings-key-input"
        type="password"
        autocomplete="off"
        data-test="custom-api-key"
        :placeholder="isEdit && store.hasKey ? '已保存，留空则沿用' : '留空表示无鉴权'"
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

    <label v-if="!store.manualModel" class="settings-field" :for="`${idPrefix}-model`">
      <span class="settings-field__label">Model</span>
      <span class="settings-field__hint">从网关的 /models 列表中选择</span>
      <select
        :id="`${idPrefix}-model`"
        v-model="store.selectedModel"
        class="settings-control settings-model-select"
        data-test="custom-model"
        :disabled="store.discoveredModels.length === 0 || store.saving"
      >
        <option value="" disabled>请选择模型</option>
        <option v-for="model in store.discoveredModels" :key="model" :value="model">{{ model }}</option>
      </select>
      <span v-if="store.discoveredModels.length === 0" class="settings-field__empty">
        先点击“获取模型”。若网关不支持 /models，将切换为手动输入
      </span>
      <span v-if="showUnavailable" class="settings-field__empty settings-field__warning" data-test="custom-unavailable">
        已保存的模型当前不可用，请重新获取后选择
      </span>
    </label>
    <label v-else class="settings-field" :for="`${idPrefix}-model-id`">
      <span class="settings-field__label">Model ID</span>
      <span class="settings-field__hint">网关不支持模型列表，请手动填写，仍需通过兼容性测试</span>
      <input
        :id="`${idPrefix}-model-id`"
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

  <footer class="settings-dialog__footer">
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
      :title="isActive ? '已是当前 Provider' : '需先通过兼容性测试才能激活（保存后即可）'"
      @click="activate"
    >
      {{ isActive ? '当前使用' : (providers.activating ? '正在切换…' : '设为当前 Provider') }}
    </button>
  </footer>
</template>

<style scoped>
/* 结构性样式统一收口在 providerSettings.css；此组件无自身私有样式。 */
</style>
