<!--
  文件名:ResizableSidebar.vue
  用途:工作台的可拖宽、可折叠侧栏(仅浏览器行为):宽度与开合由父组件控制
       (经 graphUiStore 持久化),本组件只发出新的意图;拖拽宽度被钳制在允许范围,
       侧栏宽度变化绝不触发画布坐标重算。
-->
<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'

/**
 * 可调宽 + 可折叠的工作台侧栏(仅浏览器行为)。
 *
 * 宽度与开合状态由父组件控制(经 graphUiStore 持久化);
 * 本组件只发出新的意图。拖拽调整被钳制在允许范围内,
 * 侧栏宽度变化时绝不重算图坐标。
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
    :style="{ position: 'relative', width: open ? width + 'px' : '28px' }"
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
