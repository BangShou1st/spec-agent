<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { GraphWorkspaceNodeView, GraphWorkspaceRouteView } from '@/api/types'

const props = defineProps<{
  open: boolean
  node: GraphWorkspaceNodeView | null
  sourceRoute?: GraphWorkspaceRouteView | null
  pending: boolean
  finalized?: boolean
}>()

const emit = defineEmits<{
  close: []
  submit: [label: string | null]
  'restore-source': [routeId: string]
}>()

const label = ref('')
const canSubmit = computed(() => {
  const sourceRoute = props.sourceRoute
  return !props.pending && props.node !== null && sourceRoute != null
    && (sourceRoute.lifecycleStatus === 'open' || sourceRoute.lifecycleStatus === 'superseded')
    && props.finalized === true
})

watch(() => props.open, (open) => { if (open) label.value = '' }, { immediate: true })

function submit(): void {
  if (!canSubmit.value) return
  const trimmed = label.value.trim()
  emit('submit', trimmed.length > 0 ? trimmed : null)
}
</script>

<template>
  <div v-if="open" class="dialog-backdrop" data-test="reanswer-dialog">
    <div class="dialog" role="dialog" aria-modal="true" aria-label="重新回答">
      <h3 style="margin-top: 0">重新回答</h3>
      <p class="muted">问题保持不变；新路线会从父级前缀开始，并等待你重新回答。</p>
      <p v-if="!sourceRoute" class="info-line blocker">共享节点需要先在“当前查看”中选择一条路线。</p>
      <p v-else class="meta-text">来源：{{ sourceRoute.label ?? sourceRoute.id }}</p>
      <p v-if="sourceRoute && sourceRoute.lifecycleStatus === 'archived'" class="info-line blocker">请先恢复归档路线。</p>
      <p v-if="sourceRoute && sourceRoute.lifecycleStatus === 'deleted'" class="info-line blocker">已删除路线不能作为操作来源。</p>
      <p v-if="sourceRoute && !finalized" class="info-line blocker">该来源路线在此问题上还没有回答。</p>
      <button
        v-if="sourceRoute && sourceRoute.lifecycleStatus === 'archived'"
        class="btn"
        type="button"
        data-test="restore-source"
        :disabled="pending"
        @click="emit('restore-source', sourceRoute.id)"
      >恢复来源路线</button>
      <label class="secondary field-label">
        <span>路线名称（可选）</span>
        <input v-model="label" class="answer-input" data-test="reanswer-label" maxlength="255" placeholder="重新回答路线" />
      </label>
      <div class="dialog-actions">
        <button class="btn btn-primary" data-test="reanswer-submit" :disabled="!canSubmit" @click="submit">创建重新回答路线</button>
        <button class="btn" data-test="reanswer-cancel" :disabled="pending" @click="emit('close')">取消</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dialog-backdrop { position: fixed; inset: 0; background: rgba(15, 20, 30, 0.45); display: flex; align-items: flex-start; justify-content: center; padding: 80px 16px; z-index: 60; }
.dialog { background: var(--color-surface); border-radius: var(--radius); padding: 18px; width: 100%; max-width: 520px; box-shadow: 0 12px 32px rgba(15, 20, 30, 0.25); }
.field-label { display: block; margin-top: 12px; font-size: 13px; }
.field-label input { display: block; margin-top: 4px; }
.dialog-actions { display: flex; gap: 8px; margin-top: 14px; }
.info-line { font-size: 12px; }
.blocker { color: var(--color-warn); }
</style>
