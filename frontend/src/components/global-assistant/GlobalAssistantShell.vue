<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue'
import AssistantPanel from './AssistantPanel.vue'
import { useGlobalAssistantStore } from '@/stores/globalAssistantStore'

const store = useGlobalAssistantStore()

const open = computed(() => store.panelOpen)
const running = computed(() => store.isRunning)

onMounted(() => {
  store.initPanel()
  window.addEventListener('keydown', onKeydown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
})

function onKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Escape') return
  const target = event.target as HTMLElement | null
  if (target && (target.tagName === 'TEXTAREA' || target.tagName === 'INPUT')) {
    if ((event as KeyboardEvent).isComposing) return
  }
  if (store.panelOpen) store.setPanelOpen(false)
}

function toggle(): void {
  store.togglePanel()
}
</script>

<template>
  <div class="ga-shell">
    <button
      v-if="!open"
      class="ga-toggle"
      type="button"
      data-test="ga-toggle"
      :aria-expanded="open ? 'true' : 'false'"
      aria-controls="ga-drawer"
      aria-label="打开或关闭全局助手"
      :class="{ 'ga-toggle--running': running }"
      @click="toggle"
    >
      <span class="ga-toggle__icon" aria-hidden="true">✦</span>
      <span class="ga-toggle__label">助手</span>
      <span v-if="running" class="ga-toggle__pulse" aria-hidden="true" />
    </button>
    <div
      v-if="open"
      id="ga-drawer"
      class="ga-drawer"
      data-test="ga-drawer"
    >
      <AssistantPanel />
    </div>
  </div>
</template>

<style scoped>
.ga-shell { position: relative; display: flex; align-items: center; }
.ga-toggle { display: inline-flex; align-items: center; gap: 6px; padding: 6px 12px; border: 1px solid var(--color-border); border-radius: 999px; background: var(--color-surface); color: var(--color-text-secondary); font-size: 13px; }
.ga-toggle:hover { color: var(--color-text); border-color: var(--color-border-strong); }
.ga-toggle:focus-visible { outline: none; box-shadow: var(--focus-ring); border-color: var(--color-focus); }
.ga-toggle[aria-expanded="true"] { background: var(--color-accent-soft); border-color: var(--color-accent); color: var(--color-accent-strong); }
.ga-toggle__icon { font-size: 13px; line-height: 1; }
.ga-toggle__pulse { width: 8px; height: 8px; border-radius: 999px; background: var(--color-accent); }
.ga-drawer { position: fixed; top: 0; right: 0; bottom: 0; width: 400px; max-width: min(420px, 100vw); background: var(--color-surface); border-left: 1px solid var(--color-border); box-shadow: var(--shadow-float); z-index: 60; display: flex; flex-direction: column; animation: ga-drawer-in 0.2s ease-out; }
@media (max-width: 1024px) { .ga-drawer { width: min(420px, 100vw); } }
@media (prefers-reduced-motion: reduce) { .ga-drawer { animation: none; } .ga-toggle__pulse { animation: none; } }
@keyframes ga-drawer-in { from { opacity: 0; transform: translateX(12px); } to { opacity: 1; transform: none; } }
</style>
