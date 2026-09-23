<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import ProviderCard from './ProviderCard.vue'
import ProviderModelField from './ProviderModelField.vue'
import ProviderSummaryItem from './ProviderSummaryItem.vue'
import { useOpenRouterStore } from '@/features/model-settings/state/openRouterStore'
import { useProviderSettingsStore } from '@/features/model-settings/state/providerSettingsStore'
import { providerCardState } from '@/features/model-settings/presentation/providerPresentation'

/**
 * OpenRouter card. This is the provider-card paradigm the other providers are
 * aligned to: header + status pill, current-configuration summary with a
 * single 更换 API Key entry, one always-visible model field, and the
 * 保存并测试 / 重新测试 / 设为当前 Provider action row.
 */
const store = useOpenRouterStore()
const providers = useProviderSettingsStore()
const apiKey = ref('')
// 与 OpenCode 卡一致：密钥输入仅在未配置或主动更换时出现，已配置时只展示掩码。
const changingCredential = ref(false)

const isActive = computed(() => providers.activeProvider === 'OPENROUTER')
const state = computed(() => providerCardState(
  store.configured,
  store.validated,
  isActive.value,
  store.failed,
  store.validating || store.probing,
))
const canProbe = computed(() => apiKey.value.trim().length > 0 && !store.probing && !store.saving)
const showCredentialForm = computed(() => !store.configured || changingCredential.value)

/**
 * Display list contains the injected persisted value for visibility, but save
 * gating must use the true available list so an injected unavailable persisted
 * value cannot be written back.
 */
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
  if (store.selectedModel === null || !store.displayModels.includes(store.selectedModel)) {
    return false
  }
  return isUnavailableSelected.value
})
const canSave = computed(() => store.selectedModel !== null
  && store.displayModels.includes(store.selectedModel)
  && !isUnavailableSelected.value
  && !store.saving && !store.probing
  // 更换密钥或首次配置必须输入新 Key；已配置且未更换时可仅改模型（发送 null 复用已存密钥）。
  && (changingCredential.value || !store.configured ? apiKey.value.trim().length > 0 : true))
const canValidate = computed(() => store.configured && !store.validating && !store.saving)
const canActivate = computed(() => store.configured && store.validated && !isActive.value && !providers.activating)

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
    changingCredential.value = false
    await store.validate()
  }
}

function beginCredentialChange(): void {
  changingCredential.value = true
}

async function cancelCredentialChange(): Promise<void> {
  changingCredential.value = false
  apiKey.value = ''
  if (store.configured) {
    await store.refreshModels()
  }
}

async function activate(): Promise<void> {
  await providers.activate('OPENROUTER')
}

onMounted(async () => {
  await store.loadStatus()
  // 与 OpenCode 卡一致：已配置时用已存密钥自动拉取模型，无需重新输入。
  if (store.configured) {
    void store.refreshModels()
  }
})
</script>

<template>
  <ProviderCard
    card-test-id="openrouter-card"
    title="OpenRouter"
    description="固定使用 Chat Completions 协议与官方模型列表，可按需过滤仅免费"
    :state="state"
    state-test-id="openrouter-state"
    :error="store.error"
    error-test-id="openrouter-error"
    summary-test-id="openrouter-current"
    :retrying="store.probing || store.saving || store.validating || store.loadingModels"
    @retry="() => store.clearError()"
  >
    <template v-if="store.configured" #summary>
      <ProviderSummaryItem label="API Key" :value="store.maskedKey" test-id="openrouter-masked" />
      <ProviderSummaryItem label="当前模型" :value="store.selectedModel" test-id="openrouter-selected" ellipsis />
      <button
        class="btn settings-action"
        type="button"
        data-test="openrouter-change-key"
        :disabled="store.probing || store.saving"
        @click="beginCredentialChange"
      >
        更换 API Key
      </button>
    </template>

    <div v-if="showCredentialForm" class="settings-form settings-form--credential">
      <label class="settings-field" for="openrouter-api-key">
        <span class="settings-field__label">{{ store.configured ? '新 API Key' : 'API Key' }}</span>
        <span class="settings-field__hint">仅用于验证与保存，不会明文返回</span>
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
      </div>
    </div>

    <div class="settings-form">
      <ProviderModelField
        test-id="openrouter-model"
        :model-value="store.selectedModel"
        :models="store.displayModels"
        :disabled="store.displayModels.length === 0 || store.saving"
        :loading="store.loadingModels || store.loading"
        :free-only="store.freeOnly"
        free-toggle-id="openrouter-free-only"
        :hint="store.configured ? '使用当前已保存的 API Key 获取，不需要重新输入密钥' : '展示当前可用模型；付费模型保存前会做兼容性测试'"
        :empty-text="store.configured ? '暂无可用模型，请点击“刷新模型”' : '暂无可用模型，请先验证 Key'"
        :warning-text="showUnavailable ? '已保存的模型当前不可用，请重新验证后选择' : null"
        @update:model-value="store.selectedModel = $event"
        @update:free-only="store.setFreeOnly"
      />
      <div v-if="store.configured" class="settings-form__action-row">
        <button
          class="btn settings-action"
          type="button"
          data-test="openrouter-refresh"
          :disabled="store.loadingModels"
          @click="() => store.refreshModels()"
        >
          {{ store.loadingModels ? '正在刷新…' : '刷新模型' }}
        </button>
      </div>
    </div>

    <template #footer>
      <button
        v-if="changingCredential"
        class="btn settings-action"
        type="button"
        data-test="openrouter-cancel-change"
        :disabled="store.probing || store.saving"
        @click="cancelCredentialChange"
      >
        取消更换
      </button>
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
    </template>
  </ProviderCard>
</template>

<style scoped>
/* 结构性样式统一收口在 providerSettings.css；此卡无自身私有样式。 */
</style>
