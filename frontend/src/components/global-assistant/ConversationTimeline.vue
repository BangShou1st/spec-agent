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
  pendingSteer?: { id: string; message: string; status: string } | null
  stoppedNotice?: boolean
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
        <div class="ga-empty__glow" aria-hidden="true" />
        <p class="ga-empty__eyebrow">SPEC AGENT · 全局助手</p>
        <p class="ga-empty__title">在 Spec Agent 里直接提问</p>
        <p class="ga-empty__desc">我会真实执行项目查找、概要读取与页面导航，并在时间线里展示每一步。运行中也可以继续输入调整方向。</p>
        <div class="ga-empty__chips">
          <span class="ga-empty__chip">找项目</span>
          <span class="ga-empty__chip">读概要</span>
          <span class="ga-empty__chip">去页面</span>
        </div>
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
      <div v-if="props.pendingSteer" class="ga-steer" data-test="ga-steer-pending" role="status">
        <span class="ga-steer__dot" aria-hidden="true" />
        <div class="ga-steer__body">
          <p class="ga-steer__title">正在调整方向…</p>
          <p class="ga-steer__msg">{{ props.pendingSteer.message }}</p>
        </div>
      </div>
      <div v-if="props.currentStatus" class="ga-status" data-test="ga-status">
        <span class="ga-status__spinner" aria-hidden="true" />
        <span>{{ props.currentStatus }}</span>
      </div>
      <p v-if="props.stoppedNotice && !props.running" class="ga-stopped" data-test="ga-stopped">已停止，可继续输入以换一种方式继续。</p>
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
 .ga-timeline-wrap { position: relative; flex: 1; min-height: 0; display: flex; flex-direction: column; background: radial-gradient(120% 60% at 50% 0%, rgba(124,58,237,0.06), transparent 60%); }
.ga-timeline { flex: 1; overflow-y: auto; padding: 14px; min-height: 0; }
.ga-empty { position: relative; overflow: hidden; border: 1px solid var(--color-border); border-radius: 18px; padding: 20px 16px; background: linear-gradient(135deg, #ffffff 0%, #f5f3ff 55%, #eff6ff 100%); box-shadow: 0 18px 44px -22px rgba(76,60,180,0.35); }
.ga-empty__glow { position: absolute; inset: -40px -30px auto; height: 120px; background: radial-gradient(60% 100% at 30% 20%, rgba(139,92,246,0.22), transparent 70%), radial-gradient(50% 100% at 75% 10%, rgba(59,130,246,0.18), transparent 70%); pointer-events: none; }
.ga-empty__eyebrow { position: relative; margin: 0 0 6px; font-size: 11px; font-weight: 700; letter-spacing: 0.1em; color: #7c3aed; }
.ga-empty__title { position: relative; margin: 0 0 6px; font-weight: 750; font-size: 16px; letter-spacing: -0.01em; }
.ga-empty__desc { position: relative; margin: 0; font-size: 12.5px; color: var(--color-text-secondary); line-height: 1.65; }
.ga-empty__chips { position: relative; display: flex; gap: 6px; margin-top: 12px; }
.ga-empty__chip { font-size: 12px; padding: 4px 10px; border-radius: 999px; background: rgba(255,255,255,0.85); border: 1px solid var(--color-border); color: var(--color-text-secondary); box-shadow: 0 4px 12px -8px rgba(30,40,90,0.4); }
.ga-steer { display: flex; gap: 10px; margin: 10px 0; padding: 12px; border-radius: 14px; background: linear-gradient(135deg, var(--color-accent-soft), rgba(255,255,255,0.8)); border: 1px solid var(--color-accent); box-shadow: 0 10px 26px -14px rgba(90,70,200,0.5); }
.ga-steer__dot { width: 10px; height: 10px; margin-top: 4px; border-radius: 999px; background: var(--color-accent); box-shadow: 0 0 0 5px var(--color-accent-soft); flex: none; animation: ga-pulse 1.6s ease-in-out infinite; }
.ga-steer__body { min-width: 0; }
.ga-steer__title { margin: 0 0 2px; font-size: 12.5px; font-weight: 700; color: var(--color-accent-strong); }
.ga-steer__msg { margin: 0; font-size: 13px; color: var(--color-text); line-height: 1.55; word-break: break-word; }
.ga-stopped { margin: 10px 0 0; padding: 8px 12px; font-size: 12.5px; color: var(--color-text-secondary); background: var(--color-surface-subtle); border: 1px solid var(--color-border); border-radius: 999px; text-align: center; }
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
