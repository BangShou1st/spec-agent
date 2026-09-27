<!--
  文件名:CustomProviderDialog.vue
  用途:自定义 Provider 的唯一编辑弹窗:创建与编辑复用同一表单、同一 store,
       仅文案不同,避免出现第二套悄悄走样的编辑实现;
       弹窗刻意不内嵌 Provider 卡片,以免卡片头与状态胶囊把字段序列拦腰截断。
-->
<script setup lang="ts">
import { computed } from 'vue'
import UiDialogShell from '@/shared/ui/UiDialogShell.vue'
import CustomProviderForm from './CustomProviderForm.vue'
import { useCustomProviderStore } from '@/features/model-settings/state/customProviderStore'

/**
 * 用户自定义 Provider 的唯一编辑器。创建与编辑是同一 store 之上的同一个表单,
 * 唯一区别是文案,因此绝不存在第二套悄悄走样的编辑实现。
 *
 * 弹窗刻意不内嵌 Provider 卡片:卡片头和状态胶囊会在弹窗内把字段序列拦腰截断。
 */
const props = defineProps<{ open: boolean; mode: 'create' | 'edit' }>()
const emit = defineEmits<{ (e: 'close'): void }>()

const store = useCustomProviderStore()

const title = computed(() => (props.mode === 'edit'
  ? `编辑「${store.displayName?.trim() || 'Custom'}」`
  : '添加自定义 Provider'))

/**
 * 创建流程保留历史沿用下来的 `custom-create-dialog` 钩子,让既有端到端契约继续有效;
 * 编辑模式则使用独立、无歧义的 id。一个组件两个 id,
 * 而不是为了让测试选择器活着再养一个多余弹窗。
 */
const testId = computed(() => (props.mode === 'edit' ? 'custom-provider-dialog' : 'custom-create-dialog'))

const description = computed(() => (props.mode === 'edit'
  ? '修改显示名称、API Format、Base URL 或 API Key。保存会重新写入配置并立即测试。'
  : '命名为它一个显示名称，填写兼容网关地址并选择模型。凭证只保存一次，不会再次完整显示。'))

function close(): void {
  emit('close')
}
</script>

<template>
  <UiDialogShell
    :open="open"
    :title="title"
    :description="description"
    :test-id="testId"
    @close="close"
  >
    <CustomProviderForm :mode="mode" id-prefix="custom-dialog" @saved="close" />
  </UiDialogShell>
</template>

<style scoped>
/* 弹窗外壳由 UiDialogShell 负责；表单样式统一收口在 providerSettings.css。 */
</style>
