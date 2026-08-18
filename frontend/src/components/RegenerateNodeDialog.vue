<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { RegenerateNodeRequest, RouteLineageNodeView } from '@/api/types'

/**
 * Deterministic regenerate dialog. On open it prefills replacementQuestion,
 * replacementPurpose, and replacement option labels/impacts from the selected
 * historical node — only user-controlled CONTENT is copied. Existing option
 * ids are never copied into the request; the runtime generates new ones.
 * Runtime-owned fields (replacementNodeId, replacementRouteId,
 * contextSnapshotId, supersedes ids, source refs, provenance) are never
 * exposed or sent.
 */
const props = defineProps<{
  open: boolean
  node: RouteLineageNodeView | null
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

const questionError = computed<string | null>(() =>
  question.value.trim().length === 0 ? 'Replacement question must not be blank.' : null,
)

const optionErrors = computed<boolean[]>(
  () => options.value.map((option) => option.label.trim().length === 0),
)

const canSubmit = computed<boolean>(() => {
  if (props.pending || questionError.value !== null) {
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
    <div class="dialog" role="dialog" aria-modal="true" aria-label="Regenerate question">
      <h3 style="margin-top: 0">Regenerate this question</h3>
      <p class="muted" style="margin-top: 0">
        Creates a replacement node and a new active route. The old route becomes
        superseded. No model is called — content below is deterministic.
      </p>

      <label class="field-label secondary">
        <span>Instruction (optional, what to change)</span>
        <textarea
          v-model="instruction"
          class="answer-input"
          data-test="regenerate-instruction"
          maxlength="2000"
          placeholder="e.g. Make the scope question narrower"
        ></textarea>
      </label>

      <label class="field-label secondary">
        <span>Replacement question</span>
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
        <span>Replacement purpose (optional)</span>
        <textarea
          v-model="purpose"
          class="answer-input"
          data-test="regenerate-purpose"
          maxlength="4000"
        ></textarea>
      </label>

      <fieldset style="border: 0; padding: 0; margin: 12px 0 0">
        <legend class="secondary" style="font-size: 13px">Replacement options (optional)</legend>
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
            placeholder="Option label"
          />
          <input
            v-model="option.impact"
            class="option-input"
            data-test="replacement-option-impact"
            maxlength="2000"
            placeholder="Impact (optional)"
          />
          <button class="btn btn-small" type="button" data-test="replacement-option-remove" @click="removeOption(index)">
            Remove
          </button>
          <span v-if="optionErrors[index]" class="field-error" data-test="replacement-option-error">
            Label must not be blank.
          </span>
        </div>
        <button class="btn btn-small" type="button" data-test="replacement-option-add" @click="addOption">
          + Add option
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
          {{ pending ? 'Regenerating…' : 'Regenerate' }}
        </button>
        <button class="btn" type="button" data-test="regenerate-cancel" :disabled="pending" @click="emit('close')">
          Cancel
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