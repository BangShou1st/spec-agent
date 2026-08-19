<script lang="ts">
// Registered through the options `components` block (not a script-setup
// import) so the template resolves <Handle> by name; unit tests can then
// stub it, while the real app renders Vue Flow's Handle as usual.
import { Handle } from '@vue-flow/core'
export default { components: { Handle } }
</script>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Position } from '@vue-flow/core'
import type { SubmitAnswerRequest } from '@/api/types'
import type { SpecAgentGraphNodeData } from '@/graph/graphProjection'

/**
 * Four-side edge anchors for adaptive routing. Every side carries one
 * invisible source handle and one invisible target handle at its midpoint;
 * lineage/replacement edges pick one pair per connection through the pure
 * selectEdgeHandles geometry rule (see graph/graphEdgeRouting.ts). The
 * handles are never visible (style.css), never receive pointer events and
 * can never start/end a connection: the flow is nodes-connectable=false
 * and every handle is explicitly non-connectable on both ends.
 */
const ANCHOR_SIDES: Position[] = [
  Position.Left,
  Position.Right,
  Position.Top,
  Position.Bottom,
]
const SOURCE_ANCHORS = ANCHOR_SIDES.map((side) => ({
  id: 'source-' + side,
  position: side,
}))
const TARGET_ANCHORS = ANCHOR_SIDES.map((side) => ({
  id: 'target-' + side,
  position: side,
}))

const props = defineProps<{
  data: SpecAgentGraphNodeData
  selected?: boolean
  submitting: boolean
  pending: boolean
}>()

const emit = defineEmits<{
  'submit-answer': [payload: SubmitAnswerRequest]
  fork: [nodeId: string]
  reanswer: [nodeId: string]
  regenerate: [nodeId: string]
}>()

/**
 * Graph node for the Phase 7.3 workspace.
 *
 * Only the backend Active node without a finalized answer is answerable;
 * answer inputs live directly inside the node. Historical nodes are
 * read-only: they show only the primary/current reading answer preview.
 * Full route membership, answer history, and provenance remain in the
 * Inspector.
 *
 * Drag safety: only the header drags. Interactive body controls (options,
 * textarea, buttons) stop click propagation so they neither drag
 * nor break multi-selection, while the non-interactive body surface still
 * reaches Vue Flow so clicking it selects the node normally.
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

/** 阅读路线（focus ?? active）在该节点上没有回答时，摘要区域显式显示等待。 */
const readingWaiting = computed(() => {
  if (props.data.primaryAnswer || !props.data.readingRouteId) {
    return null
  }
  const state = props.data.routeStates.find(
    (s) => s.routeId === props.data.readingRouteId,
  )
  return state && !state.answer ? state : null
})

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

function forkNode(): void {
  emit('fork', props.data.node.id)
}

function regenerateNode(): void {
  emit('regenerate', props.data.node.id)
}

function reanswerNode(): void {
  emit('reanswer', props.data.node.id)
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

    <!-- Adaptive edge anchors: one source + one target handle per side,
         all invisible and non-interactive (style.css + connectable flags). -->
    <Handle
      v-for="anchor in SOURCE_ANCHORS"
      :key="anchor.id"
      :id="anchor.id"
      type="source"
      :position="anchor.position"
      class="graph-question-node__handle"
      :connectable="false"
      :connectable-start="false"
      :connectable-end="false"
      aria-hidden="true"
    />
    <Handle
      v-for="anchor in TARGET_ANCHORS"
      :key="anchor.id"
      :id="anchor.id"
      type="target"
      :position="anchor.position"
      class="graph-question-node__handle"
      :connectable="false"
      :connectable-start="false"
      :connectable-end="false"
      aria-hidden="true"
    />
    <header class="graph-question-node__header" data-test="node-drag-handle" title="拖动标题栏移动节点">
      <span class="graph-question-node__kind">
        {{ data.canAnswer ? '当前问题' : '历史问题' }}
      </span>
      <span class="graph-question-node__meta">
        <template v-if="data.isShared">共享 · {{ data.routeIds.length }} 条路线</template>
        <template v-else>{{ data.routeIds.length }} 条路线</template>
      </span>
    </header>

    <div class="graph-question-node__body nodrag" data-test="node-body">
      <!-- Current answerable node: direct answer interaction -->
      <template v-if="data.canAnswer">
        <h3 class="graph-node-question" data-test="question">{{ node.question }}</h3>
        <p v-if="node.purpose" class="graph-node-purpose">{{ node.purpose }}</p>

        <label
          v-for="option in node.options"
          :key="option.id"
          class="graph-option nodrag"
          :class="{ 'graph-option--selected': selectedOptionId === option.id }"
          @click.stop
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
          @click.stop
        ></textarea>

        <button
          class="btn btn-primary graph-submit nodrag"
          data-test="submit-answer"
          :disabled="!canSubmit"
          @click.stop="submit"
        >
          {{ submitting ? '正在提交…' : '提交回答' }}
        </button>

      </template>

      <!-- Historical node: concise reading preview; details remain in Inspector. -->
      <template v-else>
        <h4 class="graph-node-question graph-node-question--compact" data-test="historical-question">
          {{ node.question }}
        </h4>

        <div
          v-if="primary"
          class="graph-answer-summary graph-answer-summary--clamped"
          data-test="answer-summary"
        >
          <span v-if="primary.selectedOptionLabel" class="graph-answer-option badge badge-open">
            {{ primary.selectedOptionLabel }}
          </span>
          <p v-if="primary.freeText" class="graph-answer-text">{{ primary.freeText }}</p>
          <span class="graph-answer-route meta-text">路线：{{ primary.routeId }}</span>
        </div>

        <!-- 阅读路线没有回答时：明确显示等待，绝不拿其他路线的 answer 冒充。 -->
        <div
          v-else-if="readingWaiting"
          class="graph-answer-summary"
          data-test="waiting-summary"
        >
          <span class="badge badge-warn">路线 {{ readingWaiting.routeId }} · 等待回答</span>
        </div>

        <div class="graph-node-actions">
          <button
            class="btn graph-action nodrag"
            data-test="fork-node"
            title="我接受现在，换未来。"
            @click.stop="forkNode"
          >
            从这里开新路线
          </button>
          <button
            class="btn graph-action nodrag"
            data-test="reanswer-node"
            title="问题没错，答案换一个。"
            @click.stop="reanswerNode"
          >
            重新选择答案
          </button>
          <button
            class="btn graph-action nodrag"
            data-test="regenerate-node"
            :disabled="isRootNode || pending"
            title="问题本身换掉。"
            @click.stop="regenerateNode"
          >
            创建替代问题
          </button>
        </div>
      </template>
    </div>
  </article>
</template>
