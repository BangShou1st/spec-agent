<script setup lang="ts">
/**
 * Confirmation dialog for destructive route actions (archive, soft-delete).
 * The delete copy states clearly that this is a SOFT delete: historical
 * runtime records are preserved and shared nodes/answers are never physically
 * deleted.
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

const title = (): string => (props.kind === 'archive' ? 'Archive route?' : 'Delete route?')

const description = (): string =>
  props.kind === 'archive'
    ? 'This archives the route. If it is the active route, the project will have no active route afterwards. Archived routes stay inspectable and can be restored later.'
    : 'This is a soft-delete of this route. Historical runtime records are preserved: nodes, answers, patches, and spec snapshots are not physically deleted, and the route remains inspectable and recoverable.'
</script>

<template>
  <div v-if="open" class="dialog-backdrop" data-test="confirm-route-action-dialog">
    <div class="dialog" role="dialog" aria-modal="true" :aria-label="title()">
      <h3 style="margin-top: 0">{{ title() }}</h3>
      <p style="margin-top: 0">
        <strong>{{ routeLabel ?? 'Route' }}</strong>
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
          {{ pending ? 'Working…' : kind === 'archive' ? 'Archive route' : 'Delete route' }}
        </button>
        <button class="btn" type="button" data-test="cancel-route-action" :disabled="pending" @click="emit('cancel')">
          Cancel
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