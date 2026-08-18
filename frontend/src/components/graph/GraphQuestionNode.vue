<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { SubmitAnswerRequest } from '@/api/types'
import type { SpecAgentGraphNodeData } from '@/graph/graphProjection'

const props = defineProps<{
  data: SpecAgentGraphNodeData
  submitting: boolean
  pending: boolean
}>()

const emit = defineEmits<{
  'submit-answer': [payload: SubmitAnswerRequest]
  'toggle-expanded': [nodeId: string]
  fork: [nodeId: string]
  regenerate: [nodeId: string]
}>()

/**
 * Graph node for the Phase 7.3 workspace.
 *
 * Only the backend Active node without a finalized answer is answerable;
 * answer inputs live directly inside the node. Historical nodes are
 * read-only: they show the primary answer summary (3-4 line clamp by
 * default) and can expand to the full per-route answers. Every body
 * control carries the Vue Flow `nodrag` class so only the header drags.
 */

const selectedOptionId = ref<string | null>(null)
const freeText = ref('')

// Local drafts never outlive the node: reset when the node identity or the
// answerability (Active-route answer identity) changes.
watch(
  () => [props.data.node.id, props.data.canAnswer] as const,
  () => {
    selectedOptionId.value = null
    freeText.value = ''
  },
)

const node = computed(() => props.data.node)
const primary = computed(() => props.data.primaryAnswer)

const canSubmit = computed(() => {
  if (!props.data.canAnswer || props.submitting) return false
  const optionChosen = props.data.node.options.length > 0 && selectedOptionId.value !== null
  const textGiven = props.data.node.allowFreeAnswer && freeText.value.trim().length > 0
  return optionChosen || textGiven
})

function submit(): void {
  if (!canSubmit.value) return
  emit('submit-answer', {
    selectedOptionId: selectedOptionId.value ?? null,
    freeText: props.data.node.allowFreeAnswer && freeText.value.trim().length > 0
      ? freeText.value.trim()
      : null,
  })
}

function toggleExpanded(): void {
  emit('toggle-expanded', props.data.node.id)
}

function forkNode(): void {
  emit('fork', props.data.node.id)
}

function regenerateNode(): void {
  emit('regenerate', props.data.node.id)
}

const isRootNode = computed(() => props.data.node.parentNodeId === null)
</script>

<template>
  <article
    :class="[
      'graph-question-node',
      {
        'graph-question-node--current': data.canAnswer,
        'graph-question-node--historical': !data.canAnswer,
        'graph-question-node--shared': data.isShared,
      },
    ]"
    data-test="graph-question-node"
    :data-node-id="data.node.id"
  >
    <header class="graph-question-node__header" data-test="node-drag-handle" title="拖动标题栏移动节点">
      <span class="graph-question-node__kind">
        {{ data.canAnswer ? '当前问题' : '历史问题' }}
      </span>
      <span class="graph-question-node__meta">
        <template v-if="data.isShared">共享 · {{ data.routeIds.length }} 条路线</template>
        <template v-else>{{ data.routeIds.length }} 条路线</template>
      </span>
    </header>

    <div class="graph-question-node__body nodrag" data-test="node-body" @click.stop>
      <!-- Current answerable node: direct answer interaction -->
      <template v-if="data.canAnswer">
        <h3 class="graph-node-question" data-test="question">{{ node.question }}</h3>
        <p v-if="node.purpose" class="graph-node-purpose">{{ node.purpose }}</p>

        <label
          v-for="option in node.options"
          :key="option.id"
          class="graph-option"
          :class="{ 'graph-option--selected': selectedOptionId === option.id }"
        >
          <input
            type="radio"
            name="graph-answer-option"
            :value="option.id"
            v-model="selectedOptionId"
            class="nodrag"
            data-test="option"
          />
          <span class="graph-option-label">{{ option.label }}</span>
          <span v-if="option.impact" class="graph-option-impact">{{ option.impact }}</span>
        </label>

        <textarea
          v-if="node.allowFreeAnswer"
          v-model="freeText"
          class="graph-answer-input nodrag"
          data-test="free-text"
          placeholder="补充说明（可选）"
        ></textarea>

        <button
          class="btn btn-primary graph-submit nodrag"
          data-test="submit-answer"
          :disabled="!canSubmit"
          @click="submit"
        >
          {{ submitting ? '正在提交…' : '提交回答' }}
        </button>
      </template>

      <!-- Historical answered node: read-only summary + expand -->
      <template v-else>
        <h4 class="graph-node-question graph-node-question--compact" data-test="historical-question">
          {{ node.question }}
        </h4>

        <div
          v-if="primary"
          class="graph-answer-summary"
          :class="{ 'graph-answer-summary--clamped': !data.isExpanded }"
          data-test="answer-summary"
        >
          <span v-if="primary.selectedOptionLabel" class="graph-answer-option badge badge-open">
            {{ primary.selectedOptionLabel }}
          </span>
          <p v-if="primary.freeText" class="graph-answer-text">{{ primary.freeText }}</p>
          <span class="graph-answer-route meta-text">路线：{{ primary.routeId }}</span>
        </div>

        <button
          v-if="primary || data.answers.length > 0"
          class="btn graph-toggle-expand nodrag"
          data-test="toggle-expanded"
          @click="toggleExpanded"
        >
          {{ data.isExpanded ? '收起' : '展开' }}
        </button>

        <div v-if="data.isExpanded" class="graph-node-details" data-test="node-details">
          <p v-if="node.purpose" class="graph-node-purpose">目的：{{ node.purpose }}</p>
          <ul v-if="node.options.length > 0" class="graph-option-list">
            <li v-for="option in node.options" :key="option.id" class="graph-option-row">
              {{ option.label }}<span v-if="option.impact" class="graph-option-impact"> · {{ option.impact }}</span>
            </li>
          </ul>
          <div class="graph-route-answers">
            <div
              v-for="item in data.answers"
              :key="item.routeId + item.selectedOptionId + item.freeText"
              class="graph-route-answer"
              :class="{ 'graph-route-answer--primary': item.isPrimary }"
            >
              <span class="meta-text">路线 {{ item.routeId }}</span>
              <span v-if="item.selectedOptionLabel" class="badge badge-open">{{ item.selectedOptionLabel }}</span>
              <p v-if="item.freeText" class="graph-answer-text">{{ item.freeText }}</p>
            </div>
          </div>
        </div>

        <div class="graph-node-actions">
          <button
            class="btn graph-action nodrag"
            data-test="fork-node"
            title="从该问题创建新分支路线"
            @click="forkNode"
          >
            从此分支
          </button>
          <button
            class="btn graph-action nodrag"
            data-test="regenerate-node"
            :disabled="isRootNode || pending"
            title="重新生成这个问题"
            @click="regenerateNode"
          >
            重新生成这个问题
          </button>
        </div>
      </template>
    </div>
  </article>
</template>
