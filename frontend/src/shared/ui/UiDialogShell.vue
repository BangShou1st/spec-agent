<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import AppIcon from './AppIcon.vue'
const props = defineProps<{ open: boolean; title: string; description?: string | null; testId?: string; labelledBy?: string; zIndex?: number; maxWidth?: number }>()
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
  <div v-if="open" class="ui-veil" :style="zIndex ? { zIndex } : undefined" :data-test="testId ?? 'ui-dialog'">
    <div ref="cardEl" class="ui-card" :style="maxWidth ? { maxWidth: maxWidth + 'px' } : undefined" role="dialog" aria-modal="true" :aria-label="title" :aria-describedby="description ? 'ui-dlg-desc' : undefined">
      <header class="ui-card__head">
        <div class="ui-card__titles">
          <h3 :id="titleId" class="ui-card__title">{{ title }}</h3>
          <p v-if="description" id="ui-dlg-desc" class="ui-card__desc">{{ description }}</p>
        </div>
        <button type="button" class="icon-btn" aria-label="关闭" data-test="ui-dialog-close" @click="emit('close')"><AppIcon name="close" /></button>
      </header>
      <div class="ui-card__body"><slot /></div>
      <div v-if="$slots.actions" class="ui-card__actions"><slot name="actions" /></div>
      <div v-if="$slots.footer" class="ui-card__footer"><slot name="footer" /></div>
    </div>
  </div>
</template>
<style scoped>
/* 遮罩只压暗、不遮断：底下的卡片必须仍然可辨（用户要在弹窗打开时对照着看
   自己正在配的那家 Provider）。层次感交给卡片自身的大投影，而不是把背景涂黑。 */
.ui-veil { position: fixed; inset: 0; z-index: 40; display: flex; align-items: flex-start; justify-content: center; padding: 72px 16px 24px; background: rgb(23 28 40 / 14%); }
/* 卡片高度必须被视口约束住：内容再长也只是卡片内部滚动。
   之前没有上限，一个 793px 高的表单在 768px 高的窗口里会把页脚三个按钮
   推到视口之外；而遮罩自己在滚动，一滚连背后页面的内容也被带走，
   看起来就成了「弹窗把底下的东西盖没了」。 */
.ui-card { width: 100%; max-width: 520px; display: flex; flex-direction: column; gap: 12px; max-height: calc(100vh - 96px); background: var(--color-surface); border: 1px solid var(--color-border); border-radius: 14px; padding: 20px 22px; box-shadow: 0 24px 56px rgb(16 24 40 / 20%), 0 2px 8px rgb(16 24 40 / 8%); }
.ui-card__head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; flex: none; }
.ui-card__titles { min-width: 0; flex: 1; }
.ui-card__title { margin: 0; font-size: 16px; line-height: 1.4; font-weight: 650; overflow-wrap: anywhere; }
.ui-card__desc { margin: 4px 0 0; font-size: 13px; line-height: 1.55; color: var(--color-text-secondary); overflow-wrap: anywhere; }
/* 只有正文滚动，页头页脚常驻。 */
.ui-card__body { display: flex; flex-direction: column; gap: 12px; min-width: 0; flex: 1 1 auto; min-height: 0; overflow-y: auto; }
.ui-card__actions { display: flex; justify-content: flex-end; align-items: center; gap: 8px; margin-top: 4px; flex: none; }
.ui-card__footer { min-width: 0; flex: none; }
</style>
