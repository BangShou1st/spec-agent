<script setup lang="ts">
import { computed } from 'vue'
import { agentActionLabel } from '@/presentation/agentPresentation'

/**
 * 产品化审批卡：只渲染已有 durable proposal 数据。
 * 默认只展示可读动作标签 + 已有 message + 节点上下文 + 拒绝/确认执行。
 * 不展示 proposalId / runId / raw actionFamily / 内部状态，不编造 impact。
 */
const props = defineProps<{
  actionFamily: string | null
  message: string | null
  nodeContext: string | null
  accepting: boolean
  rejecting: boolean
}>()

const emit = defineEmits<{
  accept: []
  reject: []
}>()

const actionLabel = computed(() => agentActionLabel(props.actionFamily))
const pending = computed(() => props.accepting || props.rejecting)
</script>

<template>
  <div class="proposal-card" data-test="proposal-card">
    <p class="proposal-card__action">
      <strong data-test="proposal-action">{{ actionLabel }}</strong>
      <span class="proposal-card__pending">等待你的确认</span>
    </p>
    <p v-if="message" class="proposal-card__message" data-test="proposal-message">{{ message }}</p>
    <p v-if="nodeContext" class="meta-text" data-test="proposal-context">{{ nodeContext }}</p>
    <div class="proposal-card__actions">
      <button
        class="btn btn-small"
        type="button"
        data-test="proposal-reject"
        :disabled="pending"
        @click="emit('reject')"
      >
        {{ rejecting ? '正在拒绝…' : '拒绝' }}
      </button>
      <button
        class="btn btn-small btn-primary"
        type="button"
        data-test="proposal-accept"
        :disabled="pending"
        @click="emit('accept')"
      >
        {{ accepting ? '正在确认…' : '确认执行' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.proposal-card {
  padding: 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-accent-soft);
}

.proposal-card__action {
  margin: 0 0 4px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.proposal-card__pending {
  font-size: 12px;
  color: var(--color-text-secondary);
  font-weight: 400;
}

.proposal-card__message {
  margin: 0 0 4px;
  font-size: 13px;
}

.proposal-card__actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}
</style>
