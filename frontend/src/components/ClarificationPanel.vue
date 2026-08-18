<script setup lang="ts">
import { ref, watch } from 'vue'
import type { NodeResponse, RouteResponse, SubmitAnswerRequest } from '@/api/types'

/**
 * Center clarification panel. Renders the backend-provided active node
 * (question, purpose, options, free-text support) and builds answer payloads
 * that preserve runtime-owned option ids verbatim. Drafting the first
 * question is always an explicit user action; nothing is auto-drafted on
 * page open.
 */
const props = defineProps<{
  activeRoute: RouteResponse | null
  activeNode: NodeResponse | null
  drafting: boolean
  submitting: boolean
  feedback: string | null
}>()

const emit = defineEmits<{
  draft: []
  answer: [payload: SubmitAnswerRequest]
}>()

const selectedOptionId = ref<string | null>(null)
const freeText = ref('')

// Reset local answer state whenever the backend serves a different node.
watch(
  () => props.activeNode?.id,
  () => {
    selectedOptionId.value = null
    freeText.value = ''
  },
)

const hasInput = (): boolean =>
  selectedOptionId.value !== null || freeText.value.trim().length > 0

function submit(): void {
  if (!hasInput() || props.submitting) {
    return
  }
  const payload: SubmitAnswerRequest = {}
  if (selectedOptionId.value !== null) {
    payload.selectedOptionId = selectedOptionId.value
  }
  const text = freeText.value.trim()
  if (text.length > 0) {
    payload.freeText = text
  }
  emit('answer', payload)
}
</script>

<template>
  <div class="panel">
    <div class="panel-header">Clarification</div>
    <div class="panel-body">
      <!-- No active route: honest empty state, never a manufactured question. -->
      <div v-if="activeRoute === null" class="empty-state">
        <p style="margin-top: 0"><strong>No active route</strong></p>
        <p class="muted">This project has no active route yet.</p>
      </div>

      <!-- Active route without a tip node: explicit draft action. -->
      <div v-else-if="activeNode === null" class="empty-state">
        <p style="margin-top: 0">No question has been drafted yet.</p>
        <button
          class="btn btn-primary"
          type="button"
          data-test="draft-question"
          :disabled="drafting"
          @click="emit('draft')"
        >
          {{ drafting ? 'Drafting question…' : 'Draft first question' }}
        </button>
      </div>

      <!-- Active node rendering. -->
      <div v-else>
        <h2 class="question" data-test="question">{{ activeNode.question }}</h2>
        <p v-if="activeNode.purpose" class="purpose" data-test="purpose">
          {{ activeNode.purpose }}
        </p>

        <fieldset v-if="activeNode.options.length > 0" style="border: 0; padding: 0; margin: 0 0 12px">
          <legend class="secondary" style="font-size: 13px">Choose one option</legend>
          <label
            v-for="option in activeNode.options"
            :key="option.id"
            class="option-item"
            :class="{ selected: selectedOptionId === option.id }"
          >
            <input v-model="selectedOptionId" type="radio" :value="option.id" name="option" />
            <span>
              <span class="option-label">{{ option.label }}</span>
              <span v-if="option.impact" class="option-impact"> — {{ option.impact }}</span>
            </span>
          </label>
        </fieldset>

        <label v-if="activeNode.allowFreeAnswer" class="secondary" style="font-size: 13px">
          <span style="display: block; margin-bottom: 4px">Or write your own answer</span>
          <textarea
            v-model="freeText"
            class="answer-input"
            data-test="free-text"
            placeholder="Your answer…"
            maxlength="4000"
          ></textarea>
        </label>

        <div style="margin-top: 12px">
          <button
            class="btn btn-primary"
            type="button"
            data-test="submit-answer"
            :disabled="submitting || !hasInput()"
            @click="submit"
          >
            {{ submitting ? 'Processing answer…' : 'Submit answer' }}
          </button>
          <p v-if="!hasInput() && !submitting" class="muted" style="margin: 8px 0 0">
            Select an option or enter an answer to continue.
          </p>
        </div>
      </div>

      <p v-if="feedback" class="feedback-line" data-test="feedback">{{ feedback }}</p>
    </div>
  </div>
</template>
