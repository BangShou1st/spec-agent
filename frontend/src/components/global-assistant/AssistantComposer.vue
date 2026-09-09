<script setup lang="ts">
import { computed, ref } from 'vue'
import { GA_COMPOSER_WARN_AT, GA_MAX_MESSAGE_LENGTH } from '@/api/globalAssistant'

const props = defineProps<{
  running: boolean
  sending: boolean
  cancelRequested: boolean
  waitingQuestion: string | null
  modelValue: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  send: []
  cancel: []
}>()

const composing = ref(false)
const textareaRef = ref<HTMLTextAreaElement | null>(null)

const trimmedLength = computed(() => props.modelValue.trim().length)
const canSend = computed(
  () => trimmedLength.value > 0 && trimmedLength.value <= GA_MAX_MESSAGE_LENGTH && !props.running && !props.sending,
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
  <div class="ga-composer" data-test="ga-composer">
    <p v-if="props.waitingQuestion" class="ga-composer__question" data-test="ga-clarification">
      {{ props.waitingQuestion }}
    </p>
    <label class="ga-composer__label" for="ga-composer-input">向 Spec Agent 助手提问</label>
    <textarea
      id="ga-composer-input"
      ref="textareaRef"
      class="ga-composer__input"
      data-test="ga-composer-input"
      rows="3"
      placeholder="例如：帮我找上周的 AI 邮件项目"
      :value="props.modelValue"
      :disabled="props.sending && !props.running"
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
        class="btn"
        type="button"
        data-test="ga-stop"
        :disabled="props.cancelRequested"
        @click="emit('cancel')"
      >
        {{ props.cancelRequested ? '正在停止…' : '停止' }}
      </button>
      <button
        v-else
        class="btn btn-primary"
        type="button"
        data-test="ga-send"
        :disabled="!canSend"
        @click="emit('send')"
      >
        发送
      </button>
    </div>
  </div>
</template>

<style scoped>
.ga-composer { border-top: 1px solid var(--color-border); padding: 10px 12px 12px; background: var(--color-surface); }
.ga-composer__question { margin: 0 0 8px; padding: 8px 10px; border-radius: 8px; background: var(--color-focus-soft); color: var(--color-text); font-size: 13px; border: 1px solid var(--color-border); }
.ga-composer__label { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
.ga-composer__input { width: 100%; min-height: 64px; max-height: 160px; resize: vertical; border: 1px solid var(--color-border); border-radius: 10px; padding: 8px 10px; background: var(--color-surface); line-height: 1.5; }
.ga-composer__input:focus { outline: none; border-color: var(--color-focus); box-shadow: var(--focus-ring); }
.ga-composer__row { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
.ga-composer__hint { font-size: 12px; color: var(--color-text-muted); }
.ga-composer__count { font-size: 12px; color: var(--color-text-secondary); }
.ga-composer__spacer { flex: 1; }
</style>

