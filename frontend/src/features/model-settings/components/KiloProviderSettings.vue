<!--
  文件名:KiloProviderSettings.vue
  用途:Kilo Gateway 的 Provider 卡片,对齐 OpenCode Zen 卡片范式:
       统一外壳、统一状态胶囊与统一的 保存并测试/重新测试/设为当前 操作行。
       Kilo 为 OpenAI 兼容协议(Kilo AI Gateway),免费模型匿名可用,付费模型需 API Key。
-->
<script setup lang="ts">
import { useOrganizationCapability } from '@/features/access/useOrganizationCapability'
import { computed, onMounted, ref, watch } from 'vue'
import ProviderCard from './ProviderCard.vue'
import ProviderModelField from './ProviderModelField.vue'
import ProviderSummaryItem from './ProviderSummaryItem.vue'
import { requiresModelSettings } from '@/shared/http/errorCopy'
import { useKiloSettingsStore } from '@/features/model-settings/state/kiloStore'
import { useProviderSettingsStore } from '@/features/model-settings/state/providerSettingsStore'
import { providerCardState } from '@/features/model-settings/presentation/providerPresentation'

/**
 * Kilo Gateway 卡片。请求形态由 Starter 客户端固定(Kilo AI Gateway,OpenAI 兼容
 * /chat/completions);这里统一的是卡片本身——与其他 Provider 相同的外壳、状态胶囊与
 * 保存并测试 / 重新测试 / 设为当前 Provider 操作行。
 */
type RetryAction = 'load' | 'models' | 'probe' | 'save' | null

const store = useKiloSettingsStore()
const canManage = useOrganizationCapability('CREDENTIAL_MANAGE')
const providers = useProviderSettingsStore()
const apiKey = ref('')
watch(canManage, allowed => { if (!allowed) apiKey.value = '' }, { flush: 'sync' })
const retryAction = ref<RetryAction>(null)

const isActive = computed(() => providers.activeProvider === 'KILO')
const configured = computed(() => store.status?.configured === true)
const hasSavedKey = computed(() => Boolean(store.status?.maskedKey))
const selectedRequiresKey = computed(() => store.selectedModel !== null
  && !store.freeModels.includes(store.selectedModel))
// 上游可达但被限流不是「测试失败」:连通性判定通过,限流状态单独保留。
const failed = computed(() => store.error !== null && !store.rateLimited)
const state = computed(() => providerCardState(
  configured.value,
  store.validated,
  isActive.value,
  failed.value,
  store.validating || store.probing || store.saving,
  store.rateLimited,
))

const showCredentialForm = computed(() => !configured.value || store.changingCredential
  || (selectedRequiresKey.value && !hasSavedKey.value))
const canProbe = computed(() => canManage.value && apiKey.value.trim().length > 0 && !store.probing && !store.saving)
/** 保存动作同时会在服务端验证可达性,所以是"保存并测试"一步完成。 */
const canSave = computed(() => canManage.value && store.selectedModel !== null
  && store.displayModels.includes(store.selectedModel)
  && !store.modelUnavailable
  && !store.saving && !store.probing && !store.validating
  && (!selectedRequiresKey.value || apiKey.value.trim().length > 0
    || (hasSavedKey.value && !store.changingCredential)))
const canValidate = computed(() => canManage.value && configured.value && !store.validating && !store.saving)
const canActivate = computed(() => canManage.value && configured.value && store.validated
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
  if (!canManage.value) return
  retryAction.value = 'probe'
  await store.probe(apiKey.value)
  if (!store.error) {
    retryAction.value = null
  }
}

async function saveAndTest(): Promise<void> {
  if (!canManage.value) return
  if (!store.selectedModel) {
    return
  }
  retryAction.value = 'save'
  // 全新或更换凭证走 save();凭证未变时只需改模型,两条路径都会在服务端重新校验。
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
  if (!canManage.value) { await loadStatus(); return }
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
  if (!canManage.value) return
  await providers.activate('KILO')
}

onMounted(() => {
  void loadStatus()
})
</script>

<template>
  <ProviderCard
    card-test-id="kilo-card"
    title="Kilo Gateway"
    description="Kilo AI Gateway 官方模型列表，OpenAI 兼容；免费模型匿名可用，可按需过滤仅免费"
    :state="state"
    state-test-id="kilo-state"
    :error="store.error"
    error-test-id="kilo-error"
    summary-test-id="kilo-current"
    :retrying="store.loading || store.probing || store.saving || store.loadingModels"
    :retry-label="settingsActionRequired ? '前往模型设置' : '重试'"
    @retry="retryLastAction"
  >
    <template v-if="configured" #summary>
      <ProviderSummaryItem label="API Key" :value="store.status?.maskedKey ?? '未配置（免费模型无需）'" test-id="kilo-masked" />
      <ProviderSummaryItem label="当前模型" :value="store.status?.selectedModel" test-id="kilo-selected" ellipsis />
      <button
        class="btn settings-action"
        type="button"
        v-if="canManage" data-test="kilo-change-key"
        :disabled="store.probing || store.saving"
        @click="store.beginCredentialChange()"
      >
        {{ hasSavedKey ? '更换 API Key' : '添加 API Key' }}
      </button>
    </template>

    <div v-if="canManage && showCredentialForm" class="settings-form settings-form--credential">
      <label class="settings-field" for="kilo-api-key">
        <span class="settings-field__label">{{ hasSavedKey ? '新 API Key' : 'API Key' }}</span>
        <span class="settings-field__hint">免费模型留空即可；付费模型必须填写。密钥不会显示完整内容。</span>
        <input
          id="kilo-api-key"
          v-model="apiKey"
          class="settings-control settings-key-input"
          type="password"
          autocomplete="off"
          data-test="kilo-api-key"
          :placeholder="selectedRequiresKey ? '付费模型需要 API Key' : '免费模型无需 API Key，可选填'"
        />
      </label>
      <div class="settings-form__action-row">
        <button
          class="btn settings-action"
          type="button"
          data-test="kilo-probe"
          :disabled="!canProbe"
          @click="probe"
        >
          {{ store.probing ? '正在验证…' : '验证并获取模型' }}
        </button>
      </div>
    </div>

    <div class="settings-form">
      <ProviderModelField
        test-id="kilo-model"
        :model-value="store.selectedModel"
        :models="store.displayModels"
        :disabled="!canManage || store.displayModels.length === 0 || store.saving"
        :loading="store.loadingModels"
        :free-only="store.freeOnly"
        free-toggle-id="kilo-free-only"
        :hint="hasSavedKey
          ? '免费模型使用公开凭证；付费模型使用已保存的 API Key。'
          : '免费模型无需 Key；选择付费模型时需要填写 API Key。'"
        empty-text="暂无可用模型，请点击“刷新模型”"
        :warning-text="store.modelUnavailable ? '已保存的模型当前不可用，请重新选择' : null"
        @update:model-value="canManage && (store.selectedModel = $event)"
        @update:free-only="store.setFreeOnly"
      />
      <div v-if="canManage" class="settings-form__action-row">
        <button
          class="btn settings-action"
          type="button"
          data-test="kilo-refresh"
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
        data-test="kilo-cancel-change"
        :disabled="store.probing || store.saving"
        @click="cancelCredentialChange"
      >
        取消更换
      </button>
      <button
        class="btn btn-primary settings-action"
        type="button"
        v-if="canManage" data-test="kilo-save-test"
        :disabled="!canSave"
        @click="saveAndTest"
      >
        {{ store.saving ? '正在保存并测试…' : '保存并测试' }}
      </button>
      <button
        class="btn settings-action"
        type="button"
        v-if="canManage" data-test="kilo-validate"
        :disabled="!canValidate"
        @click="() => canManage && store.validate()"
      >
        {{ store.validating ? '测试中…' : '重新测试' }}
      </button>
      <button
        class="btn settings-action"
        type="button"
        v-if="canManage" data-test="kilo-activate"
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
