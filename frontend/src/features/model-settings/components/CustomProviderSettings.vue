<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import ProviderCard from './ProviderCard.vue'
import ProviderSummaryItem from './ProviderSummaryItem.vue'
import { useCustomProviderStore } from '@/features/model-settings/state/customProviderStore'
import { useProviderSettingsStore } from '@/features/model-settings/state/providerSettingsStore'
import { providerCardState } from '@/features/model-settings/presentation/providerPresentation'

/**
 * The user-defined provider's card. It renders the stored configuration and
 * the runtime actions only — every edit lives in CustomProviderDialog, reached
 * through 「设置」. That is what makes 显示名称 editable after creation: the
 * card used to own the form and therefore never exposed the name at all.
 */
const emit = defineEmits<{ (e: 'edit'): void }>()

const store = useCustomProviderStore()
const providers = useProviderSettingsStore()
// The credential input lives only in the dialog; the card never holds a key.
const refreshing = ref(false)

const isActive = computed(() => providers.activeProvider === 'CUSTOM')
const title = computed(() => store.displayName?.trim() || 'Custom')
const state = computed(() => providerCardState(
  store.configured,
  store.validated,
  isActive.value,
  store.failed,
  store.validating || refreshing.value,
))

const canActivate = computed(() => store.configured && store.validated
  && !isActive.value && !providers.activating)

async function refresh(): Promise<void> {
  refreshing.value = true
  try {
    await store.validate()
  } finally {
    refreshing.value = false
  }
}

async function activate(): Promise<void> {
  await providers.activate('CUSTOM')
}

onMounted(() => {
  void store.loadStatus()
})
</script>

<template>
  <ProviderCard
    card-test-id="custom-card"
    :title="title"
    title-test-id="custom-title"
    description="单个自定义兼容网关，协议由你明确选择，不自动探测、不自动回退"
    :state="state"
    state-test-id="custom-state"
    :error="store.error"
    error-test-id="custom-error"
    summary-test-id="custom-current"
    :retrying="store.validating || refreshing"
    @retry="() => store.clearError()"
  >
    <template #summary>
      <ProviderSummaryItem label="API Format" :value="store.apiFormat" test-id="custom-current-format" />
      <ProviderSummaryItem label="Base URL" :value="store.baseUrl" test-id="custom-current-base" ellipsis />
      <ProviderSummaryItem label="Model" :value="store.selectedModel" test-id="custom-current-model" ellipsis />
      <ProviderSummaryItem label="API Key" :value="store.hasKey ? store.maskedKey : '未设置'" test-id="custom-masked" />
    </template>

    <p v-if="!store.configured" class="settings-field__hint" data-test="custom-unconfigured-hint">
      尚未配置。点击「设置」填写显示名称、API Format、Base URL 与模型。
    </p>

    <template #footer>
      <button
        class="btn settings-action"
        type="button"
        data-test="custom-settings"
        @click="emit('edit')"
      >
        {{ store.configured ? '设置' : '配置' }}
      </button>
      <button
        class="btn settings-action"
        type="button"
        data-test="custom-validate"
        :disabled="!store.configured || store.validating"
        @click="refresh"
      >
        {{ store.validating ? '测试中…' : '重新测试' }}
      </button>
      <button
        class="btn btn-primary settings-action"
        type="button"
        data-test="custom-activate"
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
