<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { GraphWorkspaceNodeView, GraphWorkspaceRouteView, RouteLifecycleStatus } from '@/api/types'

const props = defineProps<{
  open: boolean
  node: GraphWorkspaceNodeView | null
  routes: GraphWorkspaceRouteView[]
  activeRouteId: string | null
  pending: boolean
}>()

const emit = defineEmits<{
  close: []
  submit: [sourceRouteId: string, label: string | null]
  'restore-source': [routeId: string]
  'activate-source': [routeId: string]
}>()

const sourceRouteId = ref<string | null>(null)
const label = ref('')
const membershipRoutes = computed(() => props.node
  ? props.routes.filter((route) => route.lineageNodeIds.includes(props.node!.id))
  : [])
const selectedRoute = computed(() => membershipRoutes.value.find((route) => route.id === sourceRouteId.value) ?? null)
const allowed = computed(() => selectedRoute.value?.lifecycleStatus === 'open' && sourceRouteId.value !== null)
const lifecycleLabel = (status: RouteLifecycleStatus): string => ({ open: '开放', superseded: '已替代', archived: '已归档', deleted: '已删除' }[status])

watch(() => props.open, (open) => {
  if (open) {
    sourceRouteId.value = null
    label.value = ''
  }
}, { immediate: true })

function submit(): void {
  if (!allowed.value || !sourceRouteId.value) return
  const trimmed = label.value.trim()
  emit('submit', sourceRouteId.value, trimmed.length > 0 ? trimmed : null)
}
</script>

<template>
  <div v-if="open" class="dialog-backdrop" data-test="reanswer-dialog">
    <div class="dialog" role="dialog" aria-modal="true" aria-label="重新选择答案">
      <h3 style="margin-top: 0">重新选择答案</h3>
      <p class="muted">问题保持不变；新路线会从该问题的父级前缀开始，并等待你重新回答。</p>
      <fieldset class="reanswer-route-options">
        <legend>明确选择来源路线</legend>
        <label v-for="route in membershipRoutes" :key="route.id" class="fork-base-route">
          <input v-model="sourceRouteId" type="radio" name="reanswer-source-route" :value="route.id" data-test="reanswer-source-route" />
          <span>{{ route.label ?? route.id.slice(0, 8) }}</span>
          <span class="badge" :class="`badge-${route.lifecycleStatus}`">{{ lifecycleLabel(route.lifecycleStatus) }}</span>
          <span v-if="route.id === activeRouteId" class="badge badge-active">当前路线</span>
        </label>
      </fieldset>
      <p v-if="selectedRoute && selectedRoute.lifecycleStatus !== 'open'" class="info-line">先恢复来源路线后再继续。</p>
      <div v-if="selectedRoute && selectedRoute.lifecycleStatus !== 'open'" class="dialog-actions">
        <button class="btn" :disabled="pending" @click="emit('restore-source', selectedRoute!.id)">恢复来源路线</button>
      </div>
      <label class="secondary">路线名称（可选）<input v-model="label" class="answer-input" data-test="reanswer-label" maxlength="255" /></label>
      <div class="dialog-actions">
        <button class="btn btn-primary" data-test="reanswer-submit" :disabled="!allowed || pending" @click="submit">{{ pending ? '正在创建…' : '重新选择答案' }}</button>
        <button class="btn" data-test="reanswer-cancel" :disabled="pending" @click="emit('close')">取消</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dialog-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(15, 20, 30, 0.45);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 80px 16px;
  z-index: 60;
}

.dialog {
  background: var(--color-surface);
  border-radius: var(--radius);
  padding: 18px;
  width: 100%;
  max-width: 520px;
  box-shadow: 0 12px 32px rgba(15, 20, 30, 0.25);
}

.reanswer-route-options { border: 0; padding: 0; margin: 0 0 12px; }
.dialog-actions { display: flex; gap: 8px; margin-top: 12px; }
</style>
