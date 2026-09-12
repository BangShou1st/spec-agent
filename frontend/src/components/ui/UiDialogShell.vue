<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
const props = defineProps<{ open: boolean; title: string; description?: string | null; testId?: string; labelledBy?: string }>()
const emit = defineEmits<{ (e: 'close'): void }>()
const cardEl = ref<HTMLElement | null>(null)
const titleId = 'ui-dlg-title'
function onKey(e: KeyboardEvent): void { if (e.key === 'Escape') emit('close') }
watch(() => props.open, (v) => {
  if (v) { window.addEventListener('keydown', onKey); requestAnimationFrame(() => { const f = cardEl.value?.querySelector<HTMLElement>('button, input, select, textarea, [tabindex]'); f?.focus() }) }
  else window.removeEventListener('keydown', onKey)
}, { immediate: true })
onMounted(() => { if (props.open) window.addEventListener('keydown', onKey) })
onUnmounted(() => window.removeEventListener('keydown', onKey))
</script>
<template>
  <div v-if="open" class="ui-veil" :data-test="testId ?? 'ui-dialog'">
    <div ref="cardEl" class="ui-card" role="dialog" aria-modal="true" :aria-label="title" :aria-describedby="description ? 'ui-dlg-desc' : undefined">
      <header class="ui-card__head">
        <div class="ui-card__titles">
          <h3 :id="titleId" class="ui-card__title">{{ title }}</h3>
          <p v-if="description" id="ui-dlg-desc" class="ui-card__desc">{{ description }}</p>
        </div>
        <button type="button" class="icon-btn" aria-label="关闭" data-test="ui-dialog-close" @click="emit('close')"><svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true"><path d="M3 3l8 8M11 3l-8 8" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg></button>
      </header>
      <div class="ui-card__body"><slot /></div>
      <div v-if="$slots.actions" class="ui-card__actions"><slot name="actions" /></div>
      <div v-if="$slots.footer" class="ui-card__footer"><slot name="footer" /></div>
    </div>
  </div>
</template>
<style scoped>
.ui-veil { position: fixed; inset: 0; z-index: 40; display: flex; align-items: flex-start; justify-content: center; padding: 72px 16px 16px; background: rgb(23 28 40 / 42%); }
.ui-card { width: 100%; max-width: 520px; display: flex; flex-direction: column; gap: 12px; background: var(--color-surface); border: 1px solid var(--color-border); border-radius: 14px; padding: 20px 22px; box-shadow: var(--shadow-float); }
.ui-card__head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.ui-card__titles { min-width: 0; flex: 1; }
.ui-card__title { margin: 0; font-size: 16px; line-height: 1.4; font-weight: 650; overflow-wrap: anywhere; }
.ui-card__desc { margin: 4px 0 0; font-size: 13px; line-height: 1.55; color: var(--color-text-secondary); overflow-wrap: anywhere; }
.ui-card__body { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
.ui-card__actions { display: flex; justify-content: flex-end; align-items: center; gap: 8px; margin-top: 4px; }
.ui-card__footer { min-width: 0; }
</style>
