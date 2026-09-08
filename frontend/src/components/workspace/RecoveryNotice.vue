<script setup lang="ts">
import type { RecoveryAction, RecoveryNoticeModel } from '@/presentation/recoveryPresentation'

/**
 * 单一恢复提示：一次只渲染一个卡片、最多一个主 CTA。
 * 点击只发出语义意图，真正的 store 命令由父组件翻译执行。
 */
defineProps<{ model: RecoveryNoticeModel }>()

const emit = defineEmits<{
  action: [RecoveryAction]
}>()
</script>

<template>
  <div class="recovery-notice" role="status" data-test="recovery-notice">
    <div class="recovery-notice__text">
      <strong class="recovery-notice__title" data-test="recovery-title">{{ model.title }}</strong>
      <p class="recovery-notice__message" data-test="recovery-message">{{ model.message }}</p>
    </div>
    <button
      v-if="model.action && model.actionLabel"
      class="btn btn-primary recovery-notice__action"
      type="button"
      data-test="recovery-action"
      @click="emit('action', model.action!)"
    >
      {{ model.actionLabel }}
    </button>
  </div>
</template>

<style scoped>
.recovery-notice {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-surface);
}

.recovery-notice__text {
  flex: 1 1 auto;
  min-width: 0;
}

.recovery-notice__title {
  font-size: 13px;
}

.recovery-notice__message {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--color-text-secondary);
}
</style>
