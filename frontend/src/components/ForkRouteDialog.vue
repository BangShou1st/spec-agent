<script setup lang="ts">
import { ref, watch } from 'vue'
import type { RouteLineageNodeView } from '@/api/types'

/**
 * Fork dialog. Only user-controlled label is collected; the runtime creates
 * the new route id and activates it. Runtime-owned fields (routeId,
 * rootNodeId, tipNodeId, createdFromNodeId, activeRouteId, lifecycleStatus)
 * are never sent.
 */
const props = defineProps<{
  open: boolean
  node: RouteLineageNodeView | null
  pending: boolean
}>()

const emit = defineEmits<{
  close: []
  submit: [label: string | null]
}>()

const label = ref('')

watch(
  () => props.open,
  (open) => {
    if (open) {
      label.value = ''
    }
  },
)

function submit(): void {
  const trimmed = label.value.trim()
  emit('submit', trimmed.length > 0 ? trimmed : null)
}
</script>

<template>
  <div v-if="open" class="dialog-backdrop" data-test="fork-dialog">
    <div class="dialog" role="dialog" aria-modal="true" aria-label="Fork route">
      <h3 style="margin-top: 0">Fork route from this node</h3>
      <p class="muted" style="margin-top: 0">
        {{
          node
            ? `A new route will start from: ${node.question}`
            : 'A new route will start from the selected node.'
        }}
      </p>
      <label class="secondary" style="font-size: 13px">
        <span style="display: block; margin-bottom: 4px">Route label (optional)</span>
        <input
          v-model="label"
          class="answer-input"
          style="min-height: auto"
          data-test="fork-label"
          maxlength="255"
          placeholder="Alternative route"
        />
      </label>
      <div style="display: flex; gap: 8px; margin-top: 12px">
        <button class="btn btn-primary" type="button" data-test="fork-submit" :disabled="pending" @click="submit">
          {{ pending ? 'Forking…' : 'Create fork' }}
        </button>
        <button class="btn" type="button" data-test="fork-cancel" :disabled="pending" @click="emit('close')">
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
</style>