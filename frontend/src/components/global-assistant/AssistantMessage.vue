<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  role: 'USER' | 'ASSISTANT'
  content: string
  createdAt?: string | null
}>()

const bubbleClass = computed(() =>
  props.role === 'USER' ? 'ga-message--user' : 'ga-message--assistant',
)

function formatTime(iso?: string | null): string {
  if (!iso) return ''
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}
</script>

<template>
  <div class="ga-message" :class="bubbleClass" data-test="ga-message" :data-role="props.role">
    <div class="ga-message__bubble">
      <p class="ga-message__text">{{ props.content }}</p>
      <span v-if="formatTime(props.createdAt)" class="ga-message__time">{{ formatTime(props.createdAt) }}</span>
    </div>
  </div>
</template>

<style scoped>
.ga-message { display: flex; margin: 8px 0; }
.ga-message--user { justify-content: flex-end; }
.ga-message--assistant { justify-content: flex-start; }
.ga-message__bubble { max-width: 88%; padding: 8px 12px; border-radius: 10px; border: 1px solid var(--color-border); background: var(--color-surface); }
.ga-message--user .ga-message__bubble { background: var(--color-accent-soft); border-color: var(--color-accent); }
.ga-message--assistant .ga-message__bubble { background: var(--color-surface); }
.ga-message__text { margin: 0; white-space: pre-wrap; word-break: break-word; font-size: 13.5px; line-height: 1.55; }
.ga-message__time { display: block; margin-top: 4px; font-size: 11px; color: var(--color-text-muted); }
</style>

