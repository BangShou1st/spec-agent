<script setup lang="ts">
import { computed, ref, watch } from 'vue'

/**
 * 添加资源节点对话框。资源是能力的上下文来源（AI 通过能力读取有界
 * 摘录），不是已确认的需求事实。空路线时资源成为根；已有内容时挂在
 * 当前路线末端。
 */
const props = defineProps<{
  open: boolean
  pending: boolean
  routeEmpty: boolean
}>()

const emit = defineEmits<{
  close: []
  submit: [subtype: 'TEXT' | 'URL' | 'FILE', content: Record<string, unknown>]
}>()

const subtype = ref<'TEXT' | 'URL' | 'FILE'>('TEXT')
const text = ref('')
const url = ref('')

watch(() => props.open, (open) => {
  if (open) {
    subtype.value = 'TEXT'
    text.value = ''
    url.value = ''
  }
}, { immediate: true })

const canSubmit = computed(() => {
  if (props.pending) return false
  if (subtype.value === 'TEXT') return text.value.trim().length > 0
  if (subtype.value === 'URL') return url.value.trim().length > 0
  return url.value.trim().length > 0 || text.value.trim().length > 0
})

function submit(): void {
  if (!canSubmit.value) return
  const content: Record<string, unknown> = {}
  if (subtype.value === 'TEXT') {
    content.text = text.value.trim()
  } else {
    if (url.value.trim()) content.url = url.value.trim()
    if (text.value.trim()) content.text = text.value.trim()
  }
  emit('submit', subtype.value, content)
}
</script>

<template>
  <div v-if="open" class="dialog-backdrop" data-test="resource-dialog">
    <div class="dialog" role="dialog" aria-modal="true" aria-label="添加资源">
      <h3 style="margin-top: 0">添加资源</h3>
      <p class="muted" style="margin-top: 0">
        {{ routeEmpty ? '资源将成为该路线的第一个节点。' : '资源将挂在当前路线末端。' }}
        AI 之后可以通过能力读取资源的有界摘录（保留来源引用），资源本身不是已确认的需求。
      </p>

      <label class="secondary field-label">
        <span>资源类型</span>
        <select v-model="subtype" class="answer-input" data-test="resource-subtype">
          <option value="TEXT">文本</option>
          <option value="URL">链接</option>
          <option value="FILE">文件（粘贴内容或链接）</option>
        </select>
      </label>

      <label v-if="subtype === 'URL' || subtype === 'FILE'" class="secondary field-label">
        <span>链接地址</span>
        <input v-model="url" class="answer-input" data-test="resource-url" placeholder="https://…" />
      </label>

      <label class="secondary field-label">
        <span>{{ subtype === 'TEXT' ? '资源内容' : '内容摘录（可选）' }}</span>
        <textarea
          v-model="text"
          class="answer-input"
          data-test="resource-text"
          rows="5"
          placeholder="粘贴文本内容…"
        ></textarea>
      </label>

      <div class="dialog-actions">
        <button class="btn btn-primary" type="button" data-test="resource-submit" :disabled="!canSubmit" @click="submit">
          {{ pending ? '正在添加…' : '添加资源' }}
        </button>
        <button class="btn" type="button" data-test="resource-cancel" :disabled="pending" @click="emit('close')">取消</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dialog-backdrop { position: fixed; inset: 0; background: rgba(15, 20, 30, 0.45); display: flex; align-items: flex-start; justify-content: center; padding: 80px 16px; z-index: 40; }
.dialog { background: var(--color-surface); border-radius: var(--radius); padding: 18px; width: 100%; max-width: 520px; box-shadow: 0 12px 32px rgba(15, 20, 30, 0.25); }
.field-label { display: block; margin-top: 12px; font-size: 13px; }
.field-label input, .field-label textarea, .field-label select { display: block; margin-top: 4px; width: 100%; box-sizing: border-box; }
.dialog-actions { display: flex; gap: 8px; margin-top: 14px; }
</style>
