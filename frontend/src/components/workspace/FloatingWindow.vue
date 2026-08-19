<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import type { FloatingWindowPreference } from '@/graph/graphTypes'

export type FloatingResizeDirection =
  | 'n' | 's' | 'e' | 'w' | 'ne' | 'nw' | 'se' | 'sw'

const props = defineProps<{
  name: 'routes' | 'inspector'
  title: string
  state: FloatingWindowPreference
  zIndex: number
  minWidth: number
  maxWidth: number
  minHeight: number
  maxHeight: number
}>()

const emit = defineEmits<{
  'update:state': [value: Partial<FloatingWindowPreference>]
  focus: []
  close: []
  reset: []
}>()

let mode: 'drag' | 'resize' | null = null
let direction: FloatingResizeDirection | null = null
let startX = 0
let startY = 0
let startState: FloatingWindowPreference = { ...props.state }

function clampFloatingWindow(
  state: FloatingWindowPreference,
  viewportWidth = window.innerWidth,
  viewportHeight = window.innerHeight,
): FloatingWindowPreference {
  const width = Math.min(props.maxWidth, Math.max(props.minWidth, state.width))
  const height = Math.min(props.maxHeight, Math.max(props.minHeight, state.height))
  const titleBarHeight = 36
  return {
    ...state,
    width,
    height,
    x: Math.min(Math.max(0, viewportWidth - 48), Math.max(0, Math.min(state.x, viewportWidth - 48))),
    y: Math.min(Math.max(0, viewportHeight - titleBarHeight), Math.max(0, Math.min(state.y, viewportHeight - titleBarHeight))),
  }
}

function snap(value: number, limit: number): number {
  return Math.abs(value) <= 12 ? 0 : Math.abs(value - limit) <= 12 ? limit : value
}

function beginDrag(event: PointerEvent): void {
  if ((event.target as HTMLElement).closest('button')) return
  mode = 'drag'
  direction = null
  startX = event.clientX
  startY = event.clientY
  startState = { ...props.state }
  emit('focus')
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', end)
  event.preventDefault()
}

function beginResize(nextDirection: FloatingResizeDirection, event: PointerEvent): void {
  mode = 'resize'
  direction = nextDirection
  startX = event.clientX
  startY = event.clientY
  startState = { ...props.state }
  emit('focus')
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', end)
  event.preventDefault()
  event.stopPropagation()
}

function move(event: PointerEvent): void {
  if (!mode) return
  const dx = event.clientX - startX
  const dy = event.clientY - startY
  let next = { ...startState }
  if (mode === 'drag') {
    next.x = snap(startState.x + dx, window.innerWidth - startState.width)
    next.y = snap(startState.y + dy, window.innerHeight - startState.height)
  } else if (direction) {
    if (direction.includes('e')) next.width = startState.width + dx
    if (direction.includes('s')) next.height = startState.height + dy
    if (direction.includes('w')) {
      next.width = startState.width - dx
      next.x = startState.x + dx
    }
    if (direction.includes('n')) {
      next.height = startState.height - dy
      next.y = startState.y + dy
    }
  }
  emit('update:state', clampFloatingWindow(next))
}

function end(): void {
  mode = null
  direction = null
  window.removeEventListener('pointermove', move)
  window.removeEventListener('pointerup', end)
}

function recoverViewport(): void {
  emit('update:state', clampFloatingWindow(props.state))
}

onMounted(() => window.addEventListener('resize', recoverViewport))
onBeforeUnmount(() => {
  end()
  window.removeEventListener('resize', recoverViewport)
})
</script>

<template>
  <section
    v-if="state.open"
    class="floating-window"
    :class="`floating-window--${name}`"
    :style="{ left: state.x + 'px', top: state.y + 'px', width: state.width + 'px', height: state.height + 'px', zIndex }"
    :data-test="`floating-window-${name}`"
    @pointerdown="emit('focus')"
  >
    <header class="floating-window__titlebar" data-test="floating-window-titlebar" @pointerdown="beginDrag">
      <span>{{ title }}</span>
      <span class="floating-window__titlebar-actions">
        <button type="button" class="floating-window__button" data-test="floating-window-reset" @click.stop="emit('reset')">重置</button>
        <button type="button" class="floating-window__button" data-test="floating-window-close" @click.stop="emit('close')">×</button>
      </span>
    </header>
    <div class="floating-window__body"><slot /></div>
    <span v-for="handle in ['n', 's', 'e', 'w', 'ne', 'nw', 'se', 'sw']" :key="handle" class="floating-window__resize" :class="`floating-window__resize--${handle}`" :data-test="`resize-${handle}`" @pointerdown="beginResize(handle as FloatingResizeDirection, $event)" />
  </section>
</template>
