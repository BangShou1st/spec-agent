<script setup lang="ts">
import { computed } from 'vue'
import UiDialogShell from '@/shared/ui/UiDialogShell.vue'
import CustomProviderForm from './CustomProviderForm.vue'
import { useCustomProviderStore } from '@/features/model-settings/state/customProviderStore'

/**
 * The single editor for the user-defined provider. Create and edit are the
 * same form over the same store — the only difference is the copy — so there
 * is never a second, silently diverging edit implementation.
 *
 * The dialog deliberately does NOT embed the provider card: the card's header
 * and status pill would cut the field sequence in half inside the dialog.
 */
const props = defineProps<{ open: boolean; mode: 'create' | 'edit' }>()
const emit = defineEmits<{ (e: 'close'): void }>()

const store = useCustomProviderStore()

const title = computed(() => (props.mode === 'edit'
  ? `编辑「${store.displayName?.trim() || 'Custom'}」`
  : '添加自定义 Provider'))

/**
 * The create flow keeps the historical `custom-create-dialog` hook so existing
 * end-to-end contracts stay valid; edit mode is a distinct, unambiguous target.
 * One component, two ids — rather than a second dialog kept alive only to
 * preserve a test selector.
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
