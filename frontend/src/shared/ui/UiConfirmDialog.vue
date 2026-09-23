<script setup lang="ts">
import UiDialogShell from './UiDialogShell.vue'
defineProps<{ open: boolean; title: string; description: string; confirmLabel?: string; cancelLabel?: string; loading?: boolean; error?: string | null; testId?: string; zIndex?: number; confirmTestId?: string; cancelTestId?: string }>()
const emit = defineEmits<{ (e: 'cancel'): void; (e: 'confirm'): void }>()
</script>
<template>
  <UiDialogShell :open="open" :title="title" :description="description" :test-id="testId ?? 'ui-confirm'" :z-index="zIndex" @close="emit('cancel')">
    <slot />
    <template #actions>
      <button type="button" class="btn btn-secondary" :data-test="cancelTestId ?? 'ui-confirm-cancel'" :disabled="loading" @click="emit('cancel')">{{ cancelLabel ?? '取消' }}</button>
      <button type="button" class="btn btn-danger btn-danger--solid" :data-test="confirmTestId ?? 'ui-confirm-ok'" :disabled="loading" @click="emit('confirm')">{{ loading ? '处理中…' : (confirmLabel ?? '确认') }}</button>
    </template>
    <template v-if="error" #footer><p class="ui-confirm__error" role="alert">{{ error }}</p></template>
  </UiDialogShell>
</template>
<style scoped>
.ui-confirm__error { margin: 0; font-size: 12px; color: var(--color-danger); background: var(--color-danger-soft); border: 1px solid #ecc0bc; border-radius: 8px; padding: 6px 10px; }
</style>
