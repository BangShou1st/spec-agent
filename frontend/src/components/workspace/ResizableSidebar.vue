<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'

/**
 * Resizable + collapsible workspace sidebar (browser-only).
 *
 * Width and open state are controlled by the parent (persisted via
 * graphUiStore); this component only emits new intents. Resize drags are
 * clamped to the allowed range and the graph coordinates are never
 * recomputed when the sidebar width changes.
 */
const props = defineProps<{
  side: 'left' | 'right'
  open: boolean
  width: number
  minWidth: number
  maxWidth: number
}>()

const emit = defineEmits<{
  'update:open': [open: boolean]
  'update:width': [width: number]
}>()

const resizing = ref(false)
const startX = ref(0)
const startWidth = ref(0)

function startResize(event: PointerEvent): void {
  resizing.value = true
  startX.value = event.clientX
  startWidth.value = props.width
  window.addEventListener('pointermove', onResize)
  window.addEventListener('pointerup', stopResize)
  event.preventDefault()
}

function onResize(event: PointerEvent): void {
  if (!resizing.value) return
  const delta = props.side === 'left' ? event.clientX - startX.value : startX.value - event.clientX
  const next = Math.min(props.maxWidth, Math.max(props.minWidth, startWidth.value + delta))
  emit('update:width', next)
}

function stopResize(): void {
  resizing.value = false
  window.removeEventListener('pointermove', onResize)
  window.removeEventListener('pointerup', stopResize)
}

onBeforeUnmount(stopResize)
</script>

<template>
  <aside
    class="resizable-sidebar"
    :class="[`resizable-sidebar--${side}`, { 'resizable-sidebar--collapsed': !open }]"
    :style="{ width: open ? width + 'px' : '28px' }"
    :data-test="side + '-sidebar'"
  >
    <button
      class="resizable-sidebar__toggle"
      :data-test="`toggle-${side}`"
      :title="open ? '收起侧栏' : '展开侧栏'"
      @click="emit('update:open', !open)"
    >
      {{ open ? (side === 'left' ? '‹' : '›') : (side === 'left' ? '›' : '‹') }}
    </button>
    <div v-if="open" class="resizable-sidebar__content" data-test="sidebar-content">
      <slot />
    </div>
    <div
      v-if="open"
      class="resizable-sidebar__handle"
      :data-test="`resize-handle-${side}`"
      
      @pointerdown="startResize"
    ></div>
  </aside>
</template>
