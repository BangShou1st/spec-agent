<script setup lang="ts">
const props = defineProps<{
  name: 'history' | 'plus' | 'close' | 'check' | 'spark'
}>()
</script>

<template>
  <svg
    class="ga-icon"
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
    <g v-else-if="props.name === 'plus'">
      <path d="M8 3.2v9.6M3.2 8h9.6" />
    </g>
    <g v-else-if="props.name === 'close'">
      <path d="M4.2 4.2l7.6 7.6M11.8 4.2l-7.6 7.6" />
    </g>
    <g v-else-if="props.name === 'check'">
      <path d="M3.2 8.4l3.4 3.4 6.2-7.6" />
    </g>
    <g v-else>
      <path d="M8 2.2c.7 2.3 1.6 3.2 3.9 3.9-2.3.7-3.2 1.6-3.9 3.9-.7-2.3-1.6-3.2-3.9-3.9 2.3-.7 3.2-1.6 3.9-3.9Z" />
      <path d="M12.2 9.6c.3 1 .7 1.4 1.7 1.7-1 .3-1.4.7-1.7 1.7-.3-1-.7-1.4-1.7-1.7 1-.3 1.4-.7 1.7-1.7Z" />
    </g>
  </svg>
</template>

<style scoped>
.ga-icon {
  display: block;
  flex: none;
}
</style>
