<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import AssistantMessage from './AssistantMessage.vue'
import ToolActivityItem from './ToolActivityItem.vue'
import type { GaMessage } from '@/api/globalAssistant'
import type { GaToolActivity } from '@/stores/globalAssistantStore'

const props = defineProps<{
  messages: GaMessage[]
  activities: GaToolActivity[]
  streamingText: string
  currentStatus: string | null
  running: boolean
  waitingQuestion: string | null
}>()

const scrollRef = ref<HTMLElement | null>(null)
const nearBottom = ref(true)
const hasNewActivity = ref(false)

function isNearBottom(el: HTMLElement): boolean {
  return el.scrollHeight - el.scrollTop - el.clientHeight < 96
}

function onScroll(): void {
  const el = scrollRef.value
  if (!el) return
  nearBottom.value = isNearBottom(el)
  if (nearBottom.value) hasNewActivity.value = false
}

function scrollToBottom(): void {
  const el = scrollRef.value
  if (!el) return
  el.scrollTop = el.scrollHeight
}

function jumpToLatest(): void {
  hasNewActivity.value = false
  scrollToBottom()
}

watch(
  () => [props.messages.length, props.activities.length, props.streamingText, props.currentStatus],
  async () => {
    if (nearBottom.value) {
      await nextTick()
      scrollToBottom()
    } else {
      hasNewActivity.value = true
    }
  },
)
</script>

<template>
  <div class="ga-timeline-wrap">
    <div ref="scrollRef" class="ga-timeline" data-test="ga-timeline" role="log" aria-label="助手会话" @scroll="onScroll">
      <div v-if="props.messages.length === 0 && props.activities.length === 0 && !props.streamingText" class="ga-empty" data-test="ga-empty">
        <p class="ga-empty__title">在 Spec Agent 里直接提问</p>
        <p class="ga-empty__desc">我会真实执行项目查找、概要读取与页面导航，并在时间线里展示每一步。</p>
      </div>
      <AssistantMessage
        v-for="message in props.messages"
        :key="message.id"
        :role="message.role"
        :content="message.content"
        :created-at="message.createdAt"
      />
      <div v-if="props.activities.length > 0" class="ga-activity-group" data-test="ga-activity-group">
        <ToolActivityItem v-for="activity in props.activities" :key="activity.key" :activity="activity" />
      </div>
      <div v-if="props.currentStatus" class="ga-status" data-test="ga-status">
        <span class="ga-status__spinner" aria-hidden="true" />
        <span>{{ props.currentStatus }}</span>
      </div>
      <div v-if="props.streamingText" class="ga-streaming" data-test="ga-streaming">
        <AssistantMessage role="ASSISTANT" :content="props.streamingText" />
      </div>
    </div>
    <button
      v-if="hasNewActivity"
      class="ga-new-activity"
      type="button"
      data-test="ga-new-activity"
      @click="jumpToLatest"
    >
      有新动态，回到底部
    </button>
  </div>
</template>

<style scoped>
.ga-timeline-wrap { position: relative; flex: 1; min-height: 0; display: flex; flex-direction: column; }
.ga-timeline { flex: 1; overflow-y: auto; padding: 12px; min-height: 0; }
.ga-empty { border: 1px dashed var(--color-border); border-radius: 10px; padding: 16px 14px; background: var(--color-surface-subtle); }
.ga-empty__title { margin: 0 0 4px; font-weight: 650; font-size: 14px; }
.ga-empty__desc { margin: 0; font-size: 12.5px; color: var(--color-text-secondary); line-height: 1.6; }
.ga-activity-group { margin: 4px 0; }
.ga-status { display: flex; align-items: center; gap: 8px; margin: 8px 0; font-size: 13px; color: var(--color-text-secondary); animation: ga-fade 0.18s ease-out; }
.ga-status__spinner { width: 14px; height: 14px; border-radius: 999px; border: 2px solid var(--color-accent-soft); border-top-color: var(--color-accent); animation: ga-spin 0.9s linear infinite; flex: none; }
.ga-streaming { margin-top: 4px; }
.ga-new-activity { position: absolute; left: 50%; bottom: 10px; transform: translateX(-50%); border: 1px solid var(--color-border); background: var(--color-surface); border-radius: 999px; padding: 6px 12px; font-size: 12px; box-shadow: var(--shadow-card); }
.ga-new-activity:focus-visible { outline: none; box-shadow: var(--focus-ring); }
@media (prefers-reduced-motion: reduce) { .ga-status__spinner { animation: none; } .ga-status { animation: none; } }
@keyframes ga-spin { to { transform: rotate(360deg); } }
@keyframes ga-fade { from { opacity: 0; transform: translateY(2px); } to { opacity: 1; transform: none; } }
</style>

