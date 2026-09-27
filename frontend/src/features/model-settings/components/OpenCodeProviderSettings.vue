<!--
  文件名:OpenCodeProviderSettings.vue
  用途:OpenCode Zen 的 Provider 卡片,对齐 OpenRouter 卡片范式:
       统一外壳、统一状态胶囊与统一的 保存并测试/重新测试/设为当前 操作行,
       不再有"先存凭证再存模型"的两步流程。
       请求形态的差异(绝对直连 https://opencode.ai/zen/v1、自带请求头)由后端传输层特判。
-->
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import ProviderCard from './ProviderCard.vue'
import ProviderModelField from './ProviderModelField.vue'
import ProviderSummaryItem from './ProviderSummaryItem.vue'
import { requiresModelSettings } from '@/shared/http/errorCopy'
import { useModelSettingsStore } from '@/features/model-settings/state/modelSettingsStore'
import { useProviderSettingsStore } from '@/features/model-settings/state/providerSettingsStore'
import { providerCardState } from '@/features/model-settings/presentation/providerPresentation'

/**
 * OpenCode Zen 卡片,对齐 OpenRouter 范式。
 *
 * 请求形态的差异仍由后端传输层特判(使用自带请求头绝对直连
 * https://opencode.ai/zen/v1);这里统一的是卡片本身——与其他 Provider 相同的外壳、
 * 相同的状态胶囊、相同的 保存并测试 / 重新测试 / 设为当前 Provider 操作行,
 * 并且不再有"先 保存凭证 再 保存模型"的两步流程。
 */
type RetryAction = 'load' | 'models' | 'probe' | 'save' | null

const store = useModelSettingsStore()
const providers = useProviderSettingsStore()
const apiKey = ref('')
const retryAction = ref<RetryAction>(null)

const isActive = computed(() => providers.activeProvider === 'OPENCODE_ZEN')
const configured = computed(() => store.status?.configured === true)
const failed = computed(() => store.error !== null)
const state = computed(() => providerCardState(
  configured.value,
  store.validated,
  isActive.value,
  failed.value,
  store.validating || store.probing || store.saving,
))

const showCredentialForm = computed(() => !configured.value || store.changingCredential)
const canProbe = computed(() => apiKey.value.trim().length > 0 && !store.probing && !store.saving)
/** 保存动作同时会在服务端验证可达性,所以是"保存并测试"一步完成。 */
const canSave = computed(() => store.selectedModel !== null
  && store.displayModels.includes(store.selectedModel)
  && !store.modelUnavailable
  && !store.saving && !store.probing && !store.validating
  && (showCredentialForm.value ? apiKey.value.trim().length > 0 : true))
const canValidate = computed(() => configured.value && !store.validating && !store.saving)
const canActivate = computed(() => configured.value && store.validated
  && !isActive.value && !providers.activating)
const authenticationFailed = computed(() =>
  store.error?.code.toUpperCase().includes('AUTHENTICATION') ?? false)
const settingsActionRequired = computed(() => store.error !== null
  && requiresModelSettings(store.error.code))

async function loadStatus(): Promise<void> {
  retryAction.value = 'load'
  await store.loadStatus()
  if (!store.error) {
    retryAction.value = null
  }
}

async function refreshModels(): Promise<void> {
  retryAction.value = 'models'
  await store.refreshModels()
  if (!store.error) {
    retryAction.value = null
  }
}

async function probe(): Promise<void> {
  retryAction.value = 'probe'
  await store.probe(apiKey.value)
  if (!store.error) {
    retryAction.value = null
  }
}

async function saveAndTest(): Promise<void> {
  if (!store.selectedModel) {
    return
  }
  retryAction.value = 'save'
  // 全新或更换凭证走 save();凭证未变时只需改模型,
  // 两条路径都会在服务端重新校验。
  const ok = showCredentialForm.value
    ? await store.save(apiKey.value.trim(), store.selectedModel)
    : await store.saveModel(store.selectedModel)
  if (ok) {
    apiKey.value = ''
    retryAction.value = null
  }
}

async function cancelCredentialChange(): Promise<void> {
  apiKey.value = ''
  store.cancelCredentialChange()
  if (configured.value) {
    await refreshModels()
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
    await saveAndTest()
  } else {
    store.clearError()
  }
}

async function activate(): Promise<void> {
  await providers.activate('OPENCODE_ZEN')
}

onMounted(() => {
  void loadStatus()
})
</script>

<template>
  <ProviderCard
    card-test-id="opencode-card"
    title="OpenCode Zen"
    description="官方模型列表与固定传输协议；展示当前可用模型，可按需过滤仅免费"
    :state="state"
    state-test-id="opencode-state"
    :error="store.error"
    error-test-id="opencode-error"
    summary-test-id="opencode-current"
    :retrying="store.loading || store.probing || store.saving || store.loadingModels"
    :retry-label="settingsActionRequired ? '前往模型设置' : '重试'"
    @retry="retryLastAction"
  >
    <template v-if="configured" #summary>
      <ProviderSummaryItem label="API Key" :value="store.status?.maskedKey" test-id="opencode-masked" />
      <ProviderSummaryItem label="当前模型" :value="store.status?.selectedModel" test-id="opencode-selected" ellipsis />
      <button
        class="btn settings-action"
        type="button"
        data-test="opencode-change-key"
        :disabled="store.probing || store.saving"
        @click="store.beginCredentialChange()"
      >
        更换 API Key
      </button>
    </template>

    <div v-if="showCredentialForm" class="settings-form settings-form--credential">
      <label class="settings-field" for="opencode-api-key">
        <span class="settings-field__label">{{ configured ? '新 API Key' : 'API Key' }}</span>
        <span class="settings-field__hint">密钥只用于验证和保存，不会显示完整内容</span>
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
          class="btn settings-action"
          type="button"
          data-test="opencode-probe"
          :disabled="!canProbe"
          @click="probe"
        >
          {{ store.probing ? '正在验证…' : '验证并获取模型' }}
        </button>
      </div>
    </div>

    <div class="settings-form">
      <ProviderModelField
        test-id="opencode-model"
        :model-value="store.selectedModel"
        :models="store.displayModels"
        :disabled="store.displayModels.length === 0 || store.saving"
        :loading="store.loadingModels"
        :free-only="store.freeOnly"
        free-toggle-id="opencode-free-only"
        :hint="configured
          ? '使用当前已保存的 API Key 获取，不需要重新输入密钥'
          : '展示当前可用模型；保存前会做连通性验证'"
        :empty-text="configured ? '暂无可用模型，请点击“刷新模型”' : '暂无可用模型，请先验证 Key'"
        :warning-text="store.modelUnavailable ? '已保存的模型当前不可用，请重新选择' : null"
        @update:model-value="store.selectedModel = $event"
        @update:free-only="store.setFreeOnly"
      />
      <div v-if="configured" class="settings-form__action-row">
        <button
          class="btn settings-action"
          type="button"
          data-test="opencode-refresh"
          :disabled="store.loadingModels || store.saving"
          @click="refreshModels"
        >
          {{ store.loadingModels ? '正在刷新…' : '刷新模型' }}
        </button>
      </div>
    </div>

    <template #footer>
      <button
        v-if="store.changingCredential"
        class="btn settings-action"
        type="button"
        data-test="opencode-cancel-change"
        :disabled="store.probing || store.saving"
        @click="cancelCredentialChange"
      >
        取消更换
      </button>
      <button
        class="btn btn-primary settings-action"
        type="button"
        data-test="opencode-save-test"
        :disabled="!canSave"
        @click="saveAndTest"
      >
        {{ store.saving ? '正在保存并测试…' : '保存并测试' }}
      </button>
      <button
        class="btn settings-action"
        type="button"
        data-test="opencode-validate"
        :disabled="!canValidate"
        @click="() => store.validate()"
      >
        {{ store.validating ? '测试中…' : '重新测试' }}
      </button>
      <button
        class="btn settings-action"
        type="button"
        data-test="opencode-activate"
        :disabled="!canActivate"
        :title="isActive ? '已是当前 Provider' : '通过服务端校验后设为当前 Provider'"
        @click="activate"
      >
        {{ isActive ? '当前使用' : (providers.activating ? '正在切换…' : '设为当前 Provider') }}
      </button>
    </template>
  </ProviderCard>
</template>

<style scoped>
/* 结构性样式统一收口在 providerSettings.css；此卡无自身私有样式。 */
</style>
