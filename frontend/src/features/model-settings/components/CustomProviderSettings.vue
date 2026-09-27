<!--
  文件名:CustomProviderSettings.vue
  用途:自定义 Provider 的展示卡片:只渲染已存配置与运行时操作(设置/重新测试/设为当前),
       所有编辑都收口在 CustomProviderDialog;正因如此显示名称在创建后仍可编辑
       (此前表单挂在卡片上,卡片根本不展示名称)。
-->
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import ProviderCard from './ProviderCard.vue'
import ProviderSummaryItem from './ProviderSummaryItem.vue'
import { useCustomProviderStore } from '@/features/model-settings/state/customProviderStore'
import { useProviderSettingsStore } from '@/features/model-settings/state/providerSettingsStore'
import { providerCardState } from '@/features/model-settings/presentation/providerPresentation'

/**
 * 用户自定义 Provider 的卡片。只渲染已存配置与运行时操作,
 * 所有编辑都收口在 CustomProviderDialog,经由「设置」进入。
 * 这正是显示名称创建后仍可编辑的原因:此前卡片自己持有表单,
 * 导致名称从来不被展示。
 */
const emit = defineEmits<{ (e: 'edit'): void }>()

const store = useCustomProviderStore()
const providers = useProviderSettingsStore()
// 凭证输入只存在于弹窗中;卡片绝不持有密钥。
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
