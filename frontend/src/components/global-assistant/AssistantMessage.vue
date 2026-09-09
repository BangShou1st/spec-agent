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
    <div v-if="props.role === 'USER'" class="ga-message__bubble ga-message__bubble--user">
      <p class="ga-message__text">{{ props.content }}</p>
      <span v-if="formatTime(props.createdAt)" class="ga-message__time">{{ formatTime(props.createdAt) }}</span>
    </div>
    <div v-else class="ga-message__answer">
      <p class="ga-message__text ga-message__text--answer">{{ props.content }}</p>
      <span v-if="formatTime(props.createdAt)" class="ga-message__time ga-message__time--answer">{{ formatTime(props.createdAt) }}</span>
    </div>
  </div>
</template>

<style scoped>
.ga-message { display: flex; margin: 10px 0; }
 .ga-message--user { justify-content: flex-end; }
 .ga-message--assistant { justify-content: flex-start; }
 .ga-message__bubble--user { max-width: 86%; padding: 8px 12px; border-radius: 12px 12px 4px 12px; border: 1px solid #c9d8ff; background: #eef3ff; }
 .ga-message__answer { max-width: 100%; width: 100%; padding: 2px 2px 2px 0; }
 .ga-message__text { margin: 0; white-space: pre-wrap; word-break: break-word; font-size: 13.5px; line-height: 1.6; color: var(--color-text); }
 .ga-message__text--answer { font-size: 13.5px; line-height: 1.65; }
 .ga-message__time { display: block; margin-top: 4px; font-size: 11px; color: var(--color-text-muted); }
 .ga-message__time--answer { margin-top: 2px; }
</style>
