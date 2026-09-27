<!--
  文件名:CustomProviderForm.vue
  用途:自定义 Provider 的唯一编辑表单,创建与编辑流程共用。刻意保持扁平:
       无卡片头、无分区线、无状态胶囊,字段序列一气呵成
       (显示名称 → API Format → Base URL → API Key → 获取模型 → Model → 操作),
       不会因为外层壳的包裹把同一组字段拆成两块视觉区域。
-->
<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { productErrorMessage } from '@/shared/http/errorCopy'
import { sameOrigin } from '@/shared/net/origin'
import { useCustomProviderStore } from '@/features/model-settings/state/customProviderStore'
import { useProviderSettingsStore } from '@/features/model-settings/state/providerSettingsStore'
import { CUSTOM_FORMAT_OPTIONS, endpointPreview } from '@/features/model-settings/presentation/providerPresentation'
import ApiErrorBanner from '@/shared/ui/ApiErrorBanner.vue'
import type { CustomApiFormat } from '@/features/model-settings/api/modelProviders'

/**
 * 用户自定义 Provider 的唯一编辑界面,由创建流程与编辑流程共用。
 *
 * 刻意保持扁平:没有卡片头、没有分区线、没有状态胶囊。字段序列直线走完
 * (显示名称 → API Format → Base URL → API Key → 获取模型 → Model → 操作),
 * 同一组字段绝不因为包了个壳就被拆成视觉上分离的两块。
 */
const props = withDefaults(defineProps<{
  /** 'create' 从空白开始;'edit' 用已存配置回填所有字段。 */
  mode?: 'create' | 'edit'
  /** 由弹窗方提供 id 前缀,保证嵌套字段的 DOM id 唯一。 */
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

/**
 * 凭据复用守卫(与后端 sameOrigin 守卫一致):已配置且存有密钥时,把
 * Base URL 改到不同来源(scheme/host/有效端口)必须显式决定密钥——
 * 输入新地址自己的密钥,或清空输入框表示无鉴权。绝不把旧服务的密钥
 * 静默带到新来源。
 */
const credentialDecisionRequired = computed(() => {
  if (!store.configured || !store.hasKey || !store.savedBaseUrl) {
    return false
  }
  if (keyTouched.value) {
    return false
  }
  return !sameOrigin(store.baseUrl.trim(), store.savedBaseUrl)
})

const canDiscover = computed(() => store.baseUrl.trim().length > 0 && !store.discovering && !store.saving
  && !credentialDecisionRequired.value)
const canSaveTest = computed(() => name.value.trim().length > 0
  && store.baseUrl.trim().length > 0
  && store.selectedModel.trim().length > 0
  && !isUnavailableSelected.value
  && !store.saving && !store.validating
  && !credentialDecisionRequired.value)
const canActivate = computed(() => store.configured && store.validated
  && !isActive.value && !providers.activating)

/**
 * 发现得到的展示列表为了可见性会带上已持久化的值;
 * 保存门槛必须用真正可用的列表,否则已保存但已下架的模型会被原样写回。
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
  // 只有用户真实输入过的密钥才参与探测;否则复用已存密钥。
  const draft = keyTouched.value ? apiKey.value : null
  await store.discover(draft)
}

async function saveAndTest(): Promise<void> {
  // undefined 保留已存密钥,空串清除,非空替换。
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
      <span
        v-if="credentialDecisionRequired"
        class="settings-field__empty settings-field__warning"
        data-test="custom-origin-warning"
      >
        Base URL 已指向新的服务地址：请输入新地址对应的 API Key，或清空输入框以无鉴权保存
      </span>
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
