<!--
  文件名:ProviderCard.vue
  用途:Provider 卡片的通用外壳,由 OpenCode Zen、OpenRouter 与自定义网关共用:
       提供头部(标题/描述)、状态胶囊、错误横幅与纵向节奏,
       卡片自身内容通过插槽注入。
-->
<script setup lang="ts">
import { computed } from 'vue'
import SettingsCard from '@/shared/ui/SettingsCard.vue'
import ApiErrorBanner from '@/shared/ui/ApiErrorBanner.vue'
import ProviderStatePill from './ProviderStatePill.vue'
import { productErrorMessage } from '@/shared/http/errorCopy'
import type { ProviderState } from '@/features/model-settings/presentation/providerPresentation'

/**
 * Provider 卡片范式,从 OpenRouter 卡片抽出,由 OpenCode Zen、OpenRouter
 * 和用户的自定义网关共用。
 *
 * 每张卡片只通过插槽贡献自己的内容 —— `summary` 放当前配置项、
 * 默认插槽放表单/字段、`footer` 放操作按钮。
 * 头部、状态胶囊、错误横幅和纵向节奏因此只活在一处,绝不会各自走样。
 */
const props = defineProps<{
  title: string
  description: string
  state: ProviderState
  cardTestId: string
  /** 由卡片自己的 store 持有的错误投影;null 表示不显示横幅。 */
  error: { code: string } | null
  titleTestId?: string
  stateTestId?: string
  errorTestId?: string
  summaryTestId?: string
  retrying?: boolean
  retryLabel?: string
}>()

const emit = defineEmits<{ (e: 'retry'): void }>()

const safeErrorMessage = computed(() => productErrorMessage(props.error?.code ?? 'UNKNOWN_ERROR'))
</script>

<template>
  <SettingsCard :title="title" :description="description" :card-test-id="cardTestId" :title-test-id="titleTestId" :summary-test-id="summaryTestId">
    <template #status><ProviderStatePill :state="state" :test-id="stateTestId" /></template>
    <template #error>
      <ApiErrorBanner v-if="error" class="settings-error" :data-test="errorTestId" :message="safeErrorMessage" :code="error.code"
        :retry-label="retryLabel ?? '重试'" :retrying="retrying ?? false" @retry="emit('retry')" />
    </template>
    <template v-if="$slots.summary" #summary><slot name="summary" /></template>
    <slot />
    <template v-if="$slots.footer" #footer><slot name="footer" /></template>
  </SettingsCard>
</template>

<style scoped>
/* 结构性样式统一收口在 providerSettings.css；此组件无自身私有样式。 */
</style>
