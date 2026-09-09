<script setup lang="ts">
import { computed, ref } from 'vue'
import { GA_COMPOSER_WARN_AT, GA_MAX_MESSAGE_LENGTH } from '@/api/globalAssistant'

const props = defineProps<{
  running: boolean
  sending: boolean
  cancelRequested: boolean
  waitingQuestion: string | null
  modelValue: string
  pendingSteer?: boolean
  steerSending?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  send: []
  cancel: []
}>()

const composing = ref(false)
const textareaRef = ref<HTMLTextAreaElement | null>(null)

const trimmedLength = computed(() => props.modelValue.trim().length)
const hasPending = computed(() => !!props.pendingSteer)
const busySending = computed(() => props.sending || !!props.steerSending)
const canSend = computed(
  () => trimmedLength.value > 0 && trimmedLength.value <= GA_MAX_MESSAGE_LENGTH && !busySending.value && !hasPending.value,
)
const sendLabel = computed(() => (props.running ? '发送调整' : '发送'))
const placeholder = computed(() =>
  props.running ? '继续输入可调整方向或补充要求…' : '例如：帮我找上周的 AI 邮件项目',
)

const showCount = computed(() => props.modelValue.length >= GA_COMPOSER_WARN_AT)
const countLabel = computed(() => props.modelValue.length + ' / ' + GA_MAX_MESSAGE_LENGTH)

function focusComposer(): void {
  textareaRef.value?.focus()
}

function onInput(event: Event): void {
  emit('update:modelValue', (event.target as HTMLTextAreaElement).value)
}

function onKeydown(event: KeyboardEvent): void {
  if (composing.value) return
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    if (canSend.value) emit('send')
  }
}

function onCompositionStart(): void {
  composing.value = true
}

function onCompositionEnd(): void {
  composing.value = false
}

defineExpose({ focusComposer })
</script>

<template>
  <div class="ga-composer" data-test="ga-composer" :data-running="props.running ? 'true' : 'false'">
    <p v-if="props.waitingQuestion" class="ga-composer__question" data-test="ga-clarification">
      {{ props.waitingQuestion }}
    </p>
    <div v-if="props.running" class="ga-composer__running-line" data-test="ga-running-hint">
      <span class="ga-composer__pulse" aria-hidden="true" />
      <span>助手正在工作中，你可以继续输入来调整方向…</span>
    </div>
    <p v-if="hasPending" class="ga-composer__pending" data-test="ga-steer-pending">正在调整方向…</p>
    <label class="ga-composer__label" for="ga-composer-input">向 Spec Agent 助手提问</label>
    <textarea
      id="ga-composer-input"
      ref="textareaRef"
      class="ga-composer__input"
      data-test="ga-composer-input"
      rows="3"
      :placeholder="placeholder"
      :value="props.modelValue"
      :disabled="busySending"
      aria-describedby="ga-composer-hint"
      @input="onInput"
      @keydown="onKeydown"
      @compositionstart="onCompositionStart"
      @compositionend="onCompositionEnd"
    />
    <div class="ga-composer__row">
      <span id="ga-composer-hint" class="ga-composer__hint">Enter 发送 · Shift+Enter 换行</span>
      <span v-if="showCount" class="ga-composer__count" data-test="ga-composer-count">{{ countLabel }}</span>
      <span class="ga-composer__spacer" />
      <button
        v-if="props.running"
        class="btn ga-composer__stop"
        type="button"
        data-test="ga-stop"
        :disabled="props.cancelRequested"
        @click="emit('cancel')"
      >
        {{ props.cancelRequested ? '正在停止…' : '停止' }}
      </button>
      <button
        class="btn btn-primary ga-composer__send"
        type="button"
        data-test="ga-send"
        :disabled="!canSend"
        :title="hasPending ? '上一条调整正在生效，请稍后再发送新的要求。' : ''"
        @click="emit('send')"
      >
        {{ sendLabel }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.ga-composer { border-top: 1px solid var(--color-border); padding: 12px 14px 14px; background: linear-gradient(180deg, var(--color-surface) 0%, var(--color-surface-subtle) 100%); }
.ga-composer[data-running='true'] .ga-composer__input { border-color: var(--color-accent); box-shadow: 0 0 0 3px var(--color-accent-soft), 0 8px 24px -12px var(--color-accent-soft); }
 .ga-composer__question { margin: 0 0 8px; padding: 10px 12px; border-radius: 12px; background: linear-gradient(135deg, var(--color-focus-soft), rgba(255,255,255,0.6)); color: var(--color-text); font-size: 13px; border: 1px solid #d8cff7; line-height: 1.55; box-shadow: 0 4px 16px -8px rgba(90,70,180,0.25); }
.ga-composer__running-line { display: flex; align-items: center; gap: 8px; margin: 0 0 8px; padding: 8px 12px; border-radius: 999px; font-size: 12.5px; color: var(--color-accent-strong); background: linear-gradient(135deg, var(--color-accent-soft), rgba(255,255,255,0.7)); border: 1px solid var(--color-accent); }
.ga-composer__pulse { width: 8px; height: 8px; border-radius: 999px; background: var(--color-accent); box-shadow: 0 0 0 4px var(--color-accent-soft); animation: ga-pulse 1.6s ease-in-out infinite; flex: none; }
.ga-composer__pending { margin: 0 0 8px; font-size: 12.5px; color: var(--color-text-secondary); background: var(--color-surface); border: 1px dashed var(--color-border-strong); border-radius: 10px; padding: 7px 10px; }
.ga-composer__label { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
.ga-composer__input { width: 100%; min-height: 72px; max-height: 160px; resize: vertical; border: 1px solid var(--color-border-strong); border-radius: 14px; padding: 12px 14px; background: var(--color-surface); line-height: 1.6; font-size: 13.5px; transition: border-color 160ms ease, box-shadow 160ms ease, transform 160ms ease; box-shadow: 0 2px 12px -6px rgba(15,23,42,0.12); }
 .ga-composer__input::placeholder { color: var(--color-text-muted); }
 .ga-composer__input:hover:not(:disabled) { border-color: var(--color-focus); }
 .ga-composer__input:focus { outline: none; border-color: var(--color-focus); box-shadow: var(--focus-ring), 0 8px 28px -12px rgba(60,80,180,0.35); }
 .ga-composer__input:disabled { background: var(--color-surface-subtle); opacity: 0.85; }
.ga-composer__row { display: flex; align-items: center; gap: 8px; margin-top: 10px; }
.ga-composer__hint { font-size: 11.5px; color: var(--color-text-muted); }
 .ga-composer__count { font-size: 11.5px; color: var(--color-text-secondary); font-variant-numeric: tabular-nums; }
.ga-composer__spacer { flex: 1; }
.ga-composer__stop { border-radius: 999px; padding: 7px 16px; }
.ga-composer__send { border-radius: 999px; padding: 7px 18px; box-shadow: 0 6px 18px -8px var(--color-accent); }
.ga-composer__send:disabled { box-shadow: none; }
@keyframes ga-pulse { 0%,100% { transform: scale(1); opacity: 1; } 50% { transform: scale(0.82); opacity: 0.7; } }
@media (prefers-reduced-motion: reduce) { .ga-composer__pulse { animation: none; } }
</style>
