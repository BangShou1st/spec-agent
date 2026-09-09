<script setup lang="ts">
const props = defineProps<{
  name:
    | 'history'
    | 'plus'
    | 'close'
    | 'check'
    | 'chevron-down'
    | 'chevron-right'
    | 'undo'
    | 'redo'
    | 'zoom-in'
    | 'zoom-out'
    | 'fit'
    | 'more'
    | 'spark'
    | 'alert'
}>()
</script>

<template>
  <svg
    class="app-icon"
    viewBox="0 0 16 16"
    width="16"
    height="16"
    fill="none"
    stroke="currentColor"
    stroke-width="1.7"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    focusable="false"
  >
    <g v-if="props.name === 'history'">
      <path d="M2.8 8a5.2 5.2 0 1 1 1.5 3.7" />
      <path d="M2.8 8V5.2M2.8 8h2.8" />
      <path d="M8 5.2V8l2 1.4" />
    </g>
    <g v-else-if="props.name === 'plus'"><path d="M8 3.2v9.6M3.2 8h9.6" /></g>
    <g v-else-if="props.name === 'close'"><path d="M4.2 4.2l7.6 7.6M11.8 4.2l-7.6 7.6" /></g>
    <g v-else-if="props.name === 'check'"><path d="M3.2 8.4l3.4 3.4 6.2-7.6" /></g>
    <g v-else-if="props.name === 'chevron-down'"><path d="M4 6.2l4 3.6 4-3.6" /></g>
    <g v-else-if="props.name === 'chevron-right'"><path d="M6.2 4l3.6 4-3.6 4" /></g>
    <g v-else-if="props.name === 'undo'"><path d="M6.5 3.5L3 7l3.5 3.5" /><path d="M3.2 7H10a3.2 3.2 0 0 1 0 6.4H8" /></g>
    <g v-else-if="props.name === 'redo'"><path d="M9.5 3.5L13 7l-3.5 3.5" /><path d="M12.8 7H6a3.2 3.2 0 0 0 0 6.4h2" /></g>
    <g v-else-if="props.name === 'zoom-in'"><circle cx="7" cy="7" r="4.2" /><path d="M7 5v4M5 7h4M10.2 10.2L13.5 13.5" /></g>
    <g v-else-if="props.name === 'zoom-out'"><circle cx="7" cy="7" r="4.2" /><path d="M5 7h4M10.2 10.2L13.5 13.5" /></g>
    <g v-else-if="props.name === 'fit'"><path d="M2.8 5.8V2.8h3M13.2 5.8V2.8h-3M2.8 10.2v3h3M13.2 10.2v3h-3" /></g>
    <g v-else-if="props.name === 'more'"><circle cx="3.5" cy="8" r="1" fill="currentColor" stroke="none" /><circle cx="8" cy="8" r="1" fill="currentColor" stroke="none" /><circle cx="12.5" cy="8" r="1" fill="currentColor" stroke="none" /></g>
    <g v-else-if="props.name === 'alert'"><path d="M8 2.5L14.5 13.5H1.5L8 2.5Z" /><path d="M8 6.5v3.2" /><circle cx="8" cy="11.6" r="0.9" fill="currentColor" stroke="none" /></g>
    <g v-else>
      <path d="M8 2.2c.7 2.3 1.6 3.2 3.9 3.9-2.3.7-3.2 1.6-3.9 3.9-.7-2.3-1.6-3.2-3.9-3.9 2.3-.7 3.2-1.6 3.9-3.9Z" />
    </g>
  </svg>
</template>

<style scoped>
.app-icon { display: block; flex: none; }
</style>
