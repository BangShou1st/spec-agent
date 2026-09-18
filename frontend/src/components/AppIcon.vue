<script setup lang="ts">
const props = defineProps<{
  name:
    | 'history'
    | 'plus'
    | 'search'
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
    | 'trash'
    | 'spark'
    | 'send'
    | 'stop'
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
    <g v-else-if="props.name === 'search'"><circle cx="7" cy="7" r="4.2" /><path d="M10.2 10.2 L13.6 13.6" /></g>
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
    <g v-else-if="props.name === 'trash'">
      <path d="M2.9 4.5h10.2" />
      <path d="M6.4 4.5V3.1h3.2v1.4" />
      <path d="M4.6 4.5l.6 8.4h5.6l.6-8.4" />
      <path d="M6.9 7v3.6M9.1 7v3.6" />
    </g>
    <g v-else-if="props.name === 'send'">
      <path d="M13.7 2.3L7 9" />
      <path d="M13.7 2.3L9.6 13.7 7 9 2.3 6.4Z" />
    </g>
    <g v-else-if="props.name === 'stop'">
      <rect x="3.6" y="3.6" width="8.8" height="8.8" rx="1.6" fill="currentColor" stroke="none" />
    </g>
    <g v-else-if="props.name === 'alert'"><path d="M8 2.5L14.5 13.5H1.5L8 2.5Z" /><path d="M8 6.5v3.2" /><circle cx="8" cy="11.6" r="0.9" fill="currentColor" stroke="none" /></g>
    <!-- spark: the union above is exhaustive, so this branch is 'spark'.
         Solid fill keeps the four-point star crisp at 16px (a 1.7px stroke
         closes up at the concave waist), and the 2.6..13.4 span is symmetric
         around the viewBox centre so the glyph sits optically centred inside
         a pill next to its label. -->
    <g v-else>
      <path
        d="M8 2.6C8.5 6.7 9.3 7.5 13.4 8 9.3 8.5 8.5 9.3 8 13.4 7.5 9.3 6.7 8.5 2.6 8 6.7 7.5 7.5 6.7 8 2.6Z"
        fill="currentColor"
        stroke="none"
      />
    </g>
  </svg>
</template>

<style scoped>
.app-icon { display: block; flex: none; }
</style>
