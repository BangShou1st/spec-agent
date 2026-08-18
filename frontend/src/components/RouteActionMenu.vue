<script setup lang="ts">
import type { RouteResponse } from '@/api/types'

/**
 * 按生命周期显示路线操作。UI 仅为 UX 隐藏明显无效的操作，后端仍是权威
 * ——400/409 响应通过类型化 API 错误边界展示，前端从不本地重建。
 * `active` 不是生命周期状态；当前路线指示与生命周期徽章分离。
 */
const props = defineProps<{
  route: RouteResponse
  disabled: boolean
}>()

const emit = defineEmits<{
  activate: [routeId: string]
  restore: [routeId: string]
  archive: [routeId: string]
  delete: [routeId: string]
}>()

const isArchivedOrDeleted = (): boolean =>
  props.route.lifecycleStatus === 'archived' || props.route.lifecycleStatus === 'deleted'

const isDeleted = (): boolean => props.route.lifecycleStatus === 'deleted'
</script>

<template>
  <div class="route-actions" data-test="route-actions">
    <button
      v-if="route.lifecycleStatus === 'open' && !route.isActive"
      class="btn btn-small"
      type="button"
      data-test="activate-route"
      :disabled="disabled"
      @click="emit('activate', route.id)"
    >
      设为当前路线
    </button>

    <button
      v-if="route.lifecycleStatus !== 'open'"
      class="btn btn-small"
      type="button"
      data-test="restore-route"
      :disabled="disabled"
      @click="emit('restore', route.id)"
    >
      恢复
    </button>

    <button
      v-if="!isArchivedOrDeleted()"
      class="btn btn-small"
      type="button"
      data-test="archive-route"
      :disabled="disabled"
      @click="emit('archive', route.id)"
    >
      归档
    </button>

    <button
      v-if="!isDeleted()"
      class="btn btn-small btn-danger"
      type="button"
      data-test="delete-route"
      :disabled="disabled"
      @click="emit('delete', route.id)"
    >
      删除路线
    </button>
  </div>
</template>

<style scoped>
.route-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}

.btn-small {
  padding: 3px 10px;
  font-size: 12px;
}

.btn-danger {
  border-color: #ecc0bc;
  color: var(--color-danger);
}

.btn-danger:hover:not(:disabled) {
  background: var(--color-danger-soft);
}
</style>
