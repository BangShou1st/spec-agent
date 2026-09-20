<script setup lang="ts">
import { computed } from 'vue'
import RichAssistantText from '@/components/common/RichAssistantText.vue'
import { gaMessageTimeLabel } from '@/presentation/globalAssistantPresentation'
const props = defineProps<{
  role: 'USER' | 'ASSISTANT'
  content: string
  createdAt?: string | null
  providerLabel?: string | null
  modelId?: string | null
}>()
const bubbleClass = computed(() => (props.role === 'USER' ? 'ga-message--user' : 'ga-message--assistant'))
/** Today keeps the clock; older messages carry their real date. */
const timeLabel = computed(() => gaMessageTimeLabel(props.createdAt))
/** "供应商 · 模型" attribution, shown after the time when both parts exist. */
const attributionLabel = computed(() => {
  if (props.role !== 'ASSISTANT') return null
  const parts = [props.providerLabel?.trim(), props.modelId?.trim()].filter((p) => !!p)
  return parts.length > 0 ? parts.join(' · ') : null
})
</script>
<template>
  <div class="ga-message" :class="bubbleClass" data-test="ga-message" :data-role="props.role">
    <div v-if="props.role === 'USER'" class="ga-message__bubble ga-message__bubble--user">
      <p class="ga-message__text">{{ props.content }}</p>
      <time v-if="timeLabel" class="ga-message__time" :datetime="props.createdAt ?? undefined">{{ timeLabel }}</time>
    </div>
    <div v-else class="ga-message__answer">
      <RichAssistantText :content="props.content" />
      <div class="ga-message__meta">
        <time v-if="timeLabel" class="ga-message__time ga-message__time--answer" :datetime="props.createdAt ?? undefined">{{ timeLabel }}</time>
        <span v-if="attributionLabel" class="ga-message__attribution" data-test="ga-message-attribution">{{ attributionLabel }}</span>
      </div>
    </div>
  </div>
</template>
<style scoped>
.ga-message { display: flex; margin: 10px 0; }
.ga-message--user { justify-content: flex-end; }
.ga-message--assistant { justify-content: flex-start; }
.ga-message__bubble--user { max-width: 86%; padding: 8px 12px; border-radius: 12px 12px 4px 12px; border: 1px solid #c9d8ff; background: #eef3ff; }
.ga-message__answer { max-width: 100%; width: 100%; padding: 2px 2px 2px 0; min-width: 0; }
.ga-message__text { margin: 0; white-space: pre-wrap; word-break: break-word; font-size: 13.5px; line-height: 1.6; color: var(--color-text); }
.ga-message__meta { display: flex; align-items: baseline; gap: 8px; min-width: 0; flex-wrap: wrap; }
.ga-message__time { display: block; margin-top: 4px; font-size: 11px; color: var(--color-text-muted); }
.ga-message__time--answer { margin-top: 2px; }
.ga-message__attribution { font-size: 11px; color: var(--color-text-muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 240px; }
</style>
