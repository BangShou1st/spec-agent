<script setup lang="ts">
/**
 * 破坏性路线操作（归档、软删除）确认对话框。删除文案明确说明这是软删除：
 * 历史运行记录被保留，共享节点/回答永远不会被物理删除。
 */
const props = defineProps<{
  open: boolean
  kind: 'archive' | 'delete'
  routeLabel: string | null
  pending: boolean
}>()

const emit = defineEmits<{
  cancel: []
  confirm: []
}>()

const title = (): string => (props.kind === 'archive' ? '归档该路线？' : '删除该路线？')

const description = (): string =>
  props.kind === 'archive'
    ? '归档该路线。如果它是当前路线，之后项目将没有当前路线。归档路线仍可查看，之后可以恢复。'
    : '这是对该路线的软删除。历史运行记录会被保留：节点、回答、补丁和规格快照不会被物理删除，路线仍可查看并可恢复。'
</script>

<template>
  <div v-if="open" class="dialog-backdrop" data-test="confirm-route-action-dialog">
    <div class="dialog" role="dialog" aria-modal="true" :aria-label="title()">
      <h3 style="margin-top: 0">{{ title() }}</h3>
      <p style="margin-top: 0">
        <strong>{{ routeLabel ?? '路线' }}</strong>
      </p>
      <p class="secondary" data-test="confirm-description">{{ description() }}</p>
      <div style="display: flex; gap: 8px">
        <button
          class="btn btn-danger-solid"
          type="button"
          data-test="confirm-route-action"
          :disabled="pending"
          @click="emit('confirm')"
        >
          {{ pending ? '正在处理…' : kind === 'archive' ? '归档路线' : '删除路线' }}
        </button>
        <button class="btn" type="button" data-test="cancel-route-action" :disabled="pending" @click="emit('cancel')">
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
  z-index: 50;
}

.dialog {
  background: var(--color-surface);
  border-radius: var(--radius);
  padding: 18px;
  width: 100%;
  max-width: 480px;
  box-shadow: 0 12px 32px rgba(15, 20, 30, 0.25);
}

.btn-danger-solid {
  background: var(--color-danger);
  border-color: var(--color-danger);
  color: #ffffff;
}

.btn-danger-solid:hover:not(:disabled) {
  background: #9c1f18;
}
</style>
