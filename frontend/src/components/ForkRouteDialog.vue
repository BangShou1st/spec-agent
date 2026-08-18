<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { GraphWorkspaceNodeView, GraphWorkspaceRouteView, RouteLifecycleStatus } from '@/api/types'

/**
 * 从此分支对话框（共享节点基路线选择）。
 *
 * 现有 Runtime Fork 命令只接受 projectId + nodeId + label，不接受
 * sourceRouteId。共享历史节点属于多条路线时必须由用户明确选择基路线：
 * 非 OPEN → 先恢复这条路线；OPEN 但非当前路线 → 先设为当前路线；只有
 * 当前路线 + OPEN 才允许调用现有 Fork API。前端绝不猜测基路线，也绝不
 * 静默串联 设为当前路线 + Fork。
 */
const props = defineProps<{
  open: boolean
  node: GraphWorkspaceNodeView | null
  routes: GraphWorkspaceRouteView[]
  activeRouteId: string | null
  pending: boolean
}>()

const emit = defineEmits<{
  close: []
  submit: [label: string | null]
}>()

const label = ref('')
const baseRouteId = ref<string | null>(null)

const membershipRoutes = computed<GraphWorkspaceRouteView[]>(() => {
  if (!props.node) {
    return []
  }
  return props.routes.filter((route) => route.lineageNodeIds.includes(props.node!.id))
})

watch(
  () => props.open,
  (open) => {
    if (open) {
      label.value = ''
      // 默认选中当前路线（如果它包含该节点），否则不选。
      const active = membershipRoutes.value.find((r) => r.id === props.activeRouteId)
      baseRouteId.value = active?.id ?? null
    }
  },
  { immediate: true },
)

const selectedRoute = computed<GraphWorkspaceRouteView | null>(() =>
  membershipRoutes.value.find((route) => route.id === baseRouteId.value) ?? null,
)

const forkAllowed = computed<boolean>(() => {
  const route = selectedRoute.value
  return route !== null && route.lifecycleStatus === 'open' && route.id === props.activeRouteId
})

const blocker = computed<string | null>(() => {
  const route = selectedRoute.value
  if (!route) {
    return null
  }
  if (route.lifecycleStatus !== 'open') {
    return '先恢复这条路线（' + lifecycleLabel(route.lifecycleStatus) + '）。'
  }
  if (route.id !== props.activeRouteId) {
    return '先设为当前路线，再从此分支。'
  }
  return null
})

function lifecycleLabel(status: RouteLifecycleStatus): string {
  return { open: '开放', superseded: '已替代', archived: '已归档', deleted: '已删除' }[status]
}

function submit(): void {
  if (!forkAllowed.value) {
    return
  }
  const trimmed = label.value.trim()
  emit('submit', trimmed.length > 0 ? trimmed : null)
}
</script>

<template>
  <div v-if="open" class="dialog-backdrop" data-test="fork-dialog">
    <div class="dialog" role="dialog" aria-modal="true" aria-label="从此分支">
      <h3 style="margin-top: 0">从此分支</h3>
      <p class="muted" style="margin-top: 0">
        {{ node ? '新路线将从该问题开始：' + node.question : '新路线将从所选节点开始。' }}
      </p>

      <fieldset style="border: 0; padding: 0; margin: 0 0 10px" data-test="fork-base-routes">
        <legend class="secondary" style="font-size: 13px">选择基路线（共享节点）</legend>
        <label
          v-for="route in membershipRoutes"
          :key="route.id"
          class="fork-base-route"
          :class="{ 'fork-base-route--selected': baseRouteId === route.id }"
        >
          <input
            type="radio"
            name="fork-base-route"
            :value="route.id"
            v-model="baseRouteId"
            data-test="fork-base-route"
          />
          <span>{{ route.label ?? route.id.slice(0, 8) }}</span>
          <span class="badge" :class="`badge-${route.lifecycleStatus}`">{{ lifecycleLabel(route.lifecycleStatus) }}</span>
          <span v-if="route.id === activeRouteId" class="badge badge-active">当前路线</span>
        </label>
        <p v-if="membershipRoutes.length === 0" class="muted">该节点不属于任何路线。</p>
      </fieldset>

      <p v-if="blocker" class="info-line fork-blocker" data-test="fork-blocker">{{ blocker }}</p>

      <label class="secondary" style="font-size: 13px">
        <span style="display: block; margin-bottom: 4px">路线名称（可选）</span>
        <input
          v-model="label"
          class="answer-input"
          style="min-height: auto"
          data-test="fork-label"
          maxlength="255"
          placeholder="替代路线"
        />
      </label>
      <div style="display: flex; gap: 8px; margin-top: 12px">
        <button
          class="btn btn-primary"
          type="button"
          data-test="fork-submit"
          :disabled="!forkAllowed || pending"
          @click="submit"
        >
          {{ pending ? '正在创建…' : '创建分支' }}
        </button>
        <button class="btn" type="button" data-test="fork-cancel" :disabled="pending" @click="emit('close')">
          取消
        </button>
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
  z-index: 40;
}

.dialog {
  background: var(--color-surface);
  border-radius: var(--radius);
  padding: 18px;
  width: 100%;
  max-width: 520px;
  box-shadow: 0 12px 32px rgba(15, 20, 30, 0.25);
}

.fork-base-route {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  margin-bottom: 4px;
  cursor: pointer;
}

.fork-base-route--selected {
  border-color: var(--color-accent);
  background: var(--color-accent-soft);
}

.info-line {
  font-size: 12px;
}

.fork-blocker {
  color: var(--color-warn);
  margin: 0 0 8px;
}
</style>
