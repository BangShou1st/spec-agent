<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { GraphWorkspaceNodeView, RegenerateNodeRequest } from '@/api/types'

/**
 * 重新生成这个问题（确定性）对话框。打开时用所选历史节点的内容预填替代
 * 问题、目的与替代选项 label/impact——只复制用户可控内容。旧选项 id 永远
 * 不会被复制进请求；运行时生成新 id。不调用任何模型。
 */
const props = defineProps<{
  open: boolean
  node: GraphWorkspaceNodeView | null
  pending: boolean
}>()

const emit = defineEmits<{
  close: []
  submit: [payload: RegenerateNodeRequest]
}>()

interface EditableOption {
  label: string
  impact: string
}

const instruction = ref('')
const question = ref('')
const purpose = ref('')
const options = ref<EditableOption[]>([])

watch(
  () => props.open,
  (open) => {
    if (open && props.node) {
      instruction.value = ''
      question.value = props.node.question
      purpose.value = props.node.purpose ?? ''
      options.value = props.node.options.map((option) => ({
        label: option.label,
        impact: option.impact ?? '',
      }))
    }
  },
  { immediate: true },
)

const isRootNode = computed<boolean>(() => props.node?.parentNodeId === null)

const questionError = computed<string | null>(() =>
  question.value.trim().length === 0 ? '替代问题不能为空。' : null,
)

const optionErrors = computed<boolean[]>(
  () => options.value.map((option) => option.label.trim().length === 0),
)

const canSubmit = computed<boolean>(() => {
  if (props.pending || isRootNode.value || questionError.value !== null) {
    return false
  }
  return !optionErrors.value.some(Boolean)
})

function addOption(): void {
  options.value.push({ label: '', impact: '' })
}

function removeOption(index: number): void {
  options.value.splice(index, 1)
}

function submit(): void {
  if (!canSubmit.value) {
    return
  }
  const payload: RegenerateNodeRequest = {
    instruction: instruction.value.trim().length > 0 ? instruction.value.trim() : null,
    replacementQuestion: question.value.trim(),
    replacementPurpose: purpose.value.trim().length > 0 ? purpose.value.trim() : null,
    replacementOptions: options.value.map((option) => ({
      label: option.label.trim(),
      impact: option.impact.trim().length > 0 ? option.impact.trim() : null,
    })),
  }
  emit('submit', payload)
}
</script>

<template>
  <div v-if="open" class="dialog-backdrop" data-test="regenerate-dialog">
    <div class="dialog" role="dialog" aria-modal="true" aria-label="重新生成这个问题">
      <h3 style="margin-top: 0">重新生成这个问题</h3>
      <p class="muted" style="margin-top: 0">
        创建替代节点和新的当前路线。旧路线变为已替代。不调用任何模型——以下内容全部是确定性的。
      </p>

      <p v-if="isRootNode" class="info-line regenerate-blocker" data-test="regenerate-root-blocker">
        根问题暂不支持重新生成。
      </p>

      <label class="field-label secondary">
        <span>说明（可选，要改什么）</span>
        <textarea
          v-model="instruction"
          class="answer-input"
          data-test="regenerate-instruction"
          maxlength="2000"
          placeholder="例如：把范围问题改得更窄"
        ></textarea>
      </label>

      <label class="field-label secondary">
        <span>替代问题</span>
        <textarea
          v-model="question"
          class="answer-input"
          data-test="regenerate-question"
          maxlength="4000"
        ></textarea>
        <span v-if="questionError" class="field-error" data-test="regenerate-question-error">
          {{ questionError }}
        </span>
      </label>

      <label class="field-label secondary">
        <span>替代目的（可选）</span>
        <textarea
          v-model="purpose"
          class="answer-input"
          data-test="regenerate-purpose"
          maxlength="4000"
        ></textarea>
      </label>

      <fieldset style="border: 0; padding: 0; margin: 12px 0 0">
        <legend class="secondary" style="font-size: 13px">替代选项（可选）</legend>
        <div
          v-for="(option, index) in options"
          :key="index"
          class="replacement-option"
          data-test="replacement-option-row"
        >
          <input
            v-model="option.label"
            class="option-input"
            data-test="replacement-option-label"
            maxlength="500"
            placeholder="选项内容"
          />
          <input
            v-model="option.impact"
            class="option-input"
            data-test="replacement-option-impact"
            maxlength="2000"
            placeholder="影响（可选）"
          />
          <button class="btn btn-small" type="button" data-test="replacement-option-remove" @click="removeOption(index)">
            移除
          </button>
          <span v-if="optionErrors[index]" class="field-error" data-test="replacement-option-error">
            选项内容不能为空。
          </span>
        </div>
        <button class="btn btn-small" type="button" data-test="replacement-option-add" @click="addOption">
          + 添加选项
        </button>
      </fieldset>

      <div style="display: flex; gap: 8px; margin-top: 16px">
        <button
          class="btn btn-primary"
          type="button"
          data-test="regenerate-submit"
          :disabled="!canSubmit"
          @click="submit"
        >
          {{ pending ? '正在重新生成…' : '重新生成' }}
        </button>
        <button class="btn" type="button" data-test="regenerate-cancel" :disabled="pending" @click="emit('close')">
          取消
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dialog-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(15, 20, 30, 0.45);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 48px 16px;
  z-index: 40;
  overflow-y: auto;
}

.dialog {
  background: var(--color-surface);
  border-radius: var(--radius);
  padding: 18px;
  width: 100%;
  max-width: 640px;
  box-shadow: 0 12px 32px rgba(15, 20, 30, 0.25);
}

.field-label {
  display: block;
  margin-top: 10px;
  font-size: 13px;
}

.field-label .answer-input {
  display: block;
  min-height: 72px;
  margin-top: 4px;
}

.field-error {
  display: block;
  color: var(--color-danger);
  font-size: 12px;
  margin-top: 2px;
}

.info-line {
  font-size: 12px;
}

.regenerate-blocker {
  color: var(--color-warn);
  margin: 0 0 6px;
}

.replacement-option {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-bottom: 6px;
  flex-wrap: wrap;
}

.option-input {
  padding: 6px 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  flex: 1;
  min-width: 160px;
}

.btn-small {
  padding: 3px 10px;
  font-size: 12px;
}
</style>
