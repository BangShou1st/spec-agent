<script setup lang="ts">
import { computed, ref } from 'vue'
import type { GaToolActivity } from '@/stores/globalAssistantStore'
import AppIcon from '@/components/AppIcon.vue'

const props = defineProps<{ activity: GaToolActivity }>()

const expanded = ref(false)

const stateLabel = computed(() => {
  if (props.activity.state === 'running') return '执行中'
  if (props.activity.state === 'success') return '完成'
  return '失败'
})

const stateClass = computed(() => 'ga-tool--' + props.activity.state)

function formatDuration(ms: number | null): string | null {
  if (ms === null || ms === undefined) return null
  if (ms < 1000) return ms + 'ms'
  return (ms / 1000).toFixed(1) + 's'
}

function toggle(): void {
  expanded.value = !expanded.value
}
</script>

<template>
  <div class="ga-tool" :class="stateClass" data-test="ga-tool-activity" :data-state="props.activity.state">
    <button
      class="ga-tool__head"
      type="button"
      :aria-expanded="expanded ? 'true' : 'false'"
      :aria-label="props.activity.displayName + '，' + stateLabel"
      data-test="ga-tool-toggle"
      @click="toggle"
    >
      <span class="ga-tool__dot" aria-hidden="true">
        <span v-if="props.activity.state === 'running'" class="ga-tool__spinner" />
        <AppIcon v-else-if="props.activity.state === 'success'" name="check" />
        <AppIcon v-else name="alert" />
      </span>
      <span class="ga-tool__name">{{ props.activity.displayName }}</span>
      <span class="ga-tool__status">{{ stateLabel }}</span>
      <span class="ga-tool__chevron" aria-hidden="true"><AppIcon :name="expanded ? 'chevron-down' : 'chevron-right'" /></span>
    </button>
    <p v-if="props.activity.summary" class="ga-tool__summary">{{ props.activity.summary }}</p>
    <div v-if="expanded" class="ga-tool__detail" data-test="ga-tool-detail">
      <p v-if="props.activity.argsSummary" class="ga-tool__meta">{{ props.activity.argsSummary }}</p>
      <p v-if="formatDuration(props.activity.durationMs)" class="ga-tool__meta">
        用时 {{ formatDuration(props.activity.durationMs) }}
      </p>
      <p v-else class="ga-tool__meta muted">进行中…</p>
    </div>
  </div>
</template>

<style scoped>
.ga-tool { border: 0; border-bottom: 1px solid var(--color-border); border-radius: 0; background: transparent; margin: 0; overflow: hidden; }
 .ga-tool:last-child { border-bottom: 0; }
 .ga-tool__head { width: 100%; display: flex; align-items: center; gap: 8px; padding: 7px 4px; background: transparent; border: 0; text-align: left; border-radius: 6px; }
.ga-tool__head:focus-visible { outline: none; box-shadow: var(--focus-ring); border-radius: 10px; }
.ga-tool__dot { width: 22px; height: 22px; border-radius: 999px; display: inline-flex; align-items: center; justify-content: center; border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text-secondary); flex: none; }
.ga-tool--running .ga-tool__dot { color: var(--color-accent); border-color: var(--color-accent); }
.ga-tool--success .ga-tool__dot { color: var(--color-success); border-color: var(--color-success); background: var(--color-success-soft); }
.ga-tool--failure .ga-tool__dot { color: var(--color-danger); border-color: var(--color-danger); background: var(--color-danger-soft); }
.ga-tool__spinner { width: 12px; height: 12px; border-radius: 999px; border: 2px solid var(--color-accent-soft); border-top-color: var(--color-accent); animation: ga-spin 0.9s linear infinite; display: inline-block; }
.ga-tool__name { font-weight: 600; font-size: 12.5px; }
 .ga-tool__status { margin-left: auto; font-size: 12px; color: var(--color-text-muted); }
 .ga-tool__chevron { color: var(--color-text-muted); display: inline-flex; }
 .ga-tool__summary { margin: 0 4px 8px 30px; font-size: 12.5px; color: var(--color-text-secondary); line-height: 1.55; }
 .ga-tool__detail { border-top: 1px dashed var(--color-border); margin: 0 4px 8px 30px; padding-top: 6px; }
.ga-tool__meta { margin: 2px 0; font-size: 12px; color: var(--color-text-muted); word-break: break-word; }
@media (prefers-reduced-motion: reduce) { .ga-tool__spinner { animation: none; } }
@keyframes ga-spin { to { transform: rotate(360deg); } }
</style>
