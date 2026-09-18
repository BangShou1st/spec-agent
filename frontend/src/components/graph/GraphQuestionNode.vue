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
import { useInputDraftStore } from '@/stores/inputDraftStore'
import { phaseToCopy } from '@/graph/phaseCopy'
import RichAssistantText from '@/components/global-assistant/RichAssistantText.vue'

/**
 * Four-side edge anchors for adaptive routing. Every side carries one
 * source handle and one invisible-until-hover target handle at its midpoint;
 * lineage/replacement edges pick one pair per connection through the pure
 * selectEdgeHandles geometry rule (see graph/graphEdgeRouting.ts). Source
 * handles accept manual drag-connections (semantic relations between nodes);
 * the flow still forbids connecting anything to question-option slots.
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
  'focus-route': [routeId: string | null]
  fork: [nodeId: string]
  reanswer: [nodeId: string]
  regenerate: [nodeId: string]
  'contextual-ai': [nodeId: string]
  'retry-pending': []
  'activate-route': [routeId: string]
}>()

/**
 * Graph node for the Phase 7.3 workspace.
 *
 * Only the backend Active node without a finalized answer is answerable;
 * answer inputs live directly inside the node. Historical nodes are
 * read-only: selected (clicked) historical nodes show the complete question
 * and answer at the same fidelity as the current node; unselected ones stay
 * a compact navigation card so a long history never buries the canvas.
 * Route-by-route answer history and provenance remain in the Inspector.
 *
 * Drag safety: only the header drags. Interactive body controls (options,
 * textarea, buttons) stop click propagation so they neither drag
 * nor break multi-selection, while the non-interactive body surface still
 * reaches Vue Flow so clicking it selects the node normally.
 */

const inputDraftStore = useInputDraftStore()

// Local refs backed by the draft store. The store survives remounts,
// drags, and focus changes.
const selectedOptionId = ref<string | null>(null)
const freeText = ref('')

// Load draft from store when node identity changes; never clear existing input.
watch(
  () => [props.data.node.id, props.data.canAnswer] as const,
  () => {
    const draft = inputDraftStore.getDraft(
      props.data.projectId,
      props.data.node.id,
      props.data.readingRouteId,
    )
    if (draft) {
      selectedOptionId.value = draft.selectedOptionId
      freeText.value = draft.freeText
    } else {
      selectedOptionId.value = null
      freeText.value = ''
    }
  },
  { immediate: true },
)

// Persist draft to store on every change.
watch([selectedOptionId, freeText], () => {
  if (props.data.canAnswer) {
    inputDraftStore.setDraft(
      props.data.projectId,
      props.data.node.id,
      { selectedOptionId: selectedOptionId.value, freeText: freeText.value },
      props.data.readingRouteId,
    )
  }
})

const node = computed(() => props.data.node)
const primary = computed(() => props.data.primaryAnswer)
const isPendingCard = computed(() =>
  !props.data.canAnswer
  && props.data.node.id.startsWith('pending:')
  && props.data.runtimeStatus != null,
)

/** 单一产品化节点状态：卡片只展示这一个状态徽标，不为每个 Runtime
 * phase 单独加 badge。生成中/待处理的解释文案仍由 phaseToCopy()
 * 在 pending 内容区承担。 */
const presentationState = computed(() => {
  if (props.data.runtimeStatus === 'FAILED') return { label: '需处理', className: 'badge-danger' }
  if (props.data.runtimeStatus === 'RUNNING') return { label: '生成中', className: 'badge-open' }
  if (isPendingCard.value || props.data.runtimeStatus === 'PENDING') return { label: '待处理', className: 'badge-warn' }
  if (props.data.canAnswer) return { label: '就绪', className: 'badge-ready' }
  if (props.data.primaryAnswer) return { label: '已确认', className: 'badge-confirmed' }
  return { label: '待处理', className: 'badge-neutral' }
})

/** 显式阅读路线只作 read context 标识，不参与 Answer 内容选择。Shared
 * canonical Question 只有唯一不可变 Answer 身份，Focus 切换不改变内容。 */

/** 阅读路线 tip 上的真正 canonical 未答 Question：激活其显式所属路线后即可
 * 回答。已答节点（canonical Answer 存在）绝不出现；未选查看路线的 shared
 * 未答节点也不出现（绝不 Active/first/latest 回退，绝不制造 route-specific
 * 等待）。 */
const unansweredReadingTip = computed(() => {
  if (props.data.primaryAnswer || !props.data.readingRouteId
      || props.data.isTipOfReadingRoute !== true) {
    return null
  }
  const state = props.data.routeStates.find(
    (s) => s.routeId === props.data.readingRouteId,
  )
  return state && !state.answer ? state : null
})
const unansweredReadingTipLabel = computed(() => {
  const waiting = unansweredReadingTip.value
  if (!waiting) return '当前查看路线'
  return props.data.routeStates.find((state) => state.routeId === waiting.routeId)?.routeLabel || '当前查看路线'
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
    // Explicit target: when this card is the tip of the route the user is
    // reading (e.g. a non-Active route), the answer must be written to THAT
    // route instead of whatever route happens to be Active.
    nodeId: props.data.canonicalNodeId ?? props.data.node.id,
    routeId: props.data.readingRouteId,
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
const readingRouteOptions = computed(() => props.data.routeMembership ?? [])

/**
 * 当前查看 = 已确定时只展示（默认由 Focus / 只看这条路线 / 唯一可见归属填满），
 * 真正歧义时（多归属且都可见且无 Focus）才给出选择器。
 * 只有一条候选时选择器没有可选项意义 —— 那就是"下拉碍事"的来源。
 */
const readingRouteLabel = computed<string | null>(() => {
  const routeId = props.data.readingRouteId
  if (!routeId) return null
  const membership = readingRouteOptions.value.find((option) => option.routeId === routeId)
  return membership?.label ?? '已选路线'
})
const needsReadingRouteChoice = computed(() =>
  props.data.readingRouteId === null && readingRouteOptions.value.length > 1,
)

/**
 * 被选中的历史节点展开为"完整问答"：与当前问题节点同等规格地展示完整问题、
 * 目的、全部选项（标出实际选择）与自由文本回答（富文本渲染）。
 *
 * 未选中时仍保持紧凑导航卡，避免画布被长文本铺满；逐路线的回答历史继续留在
 * Inspector 中（那里才是多路线事实的唯一权威视图）。
 */
const showFullHistory = computed(() => props.selected === true)

/** 全部选项 + 该项是否就是实际提交的选择（只读展示，回答仍在 Inspector/历史中）。 */
const historyOptions = computed(() => {
  const chosen = primary.value?.selectedOptionId ?? null
  return node.value.options.map((option) => ({ option, chosen: option.id === chosen }))
})

function setReadingRoute(event: Event): void {
  const value = (event.target as HTMLSelectElement).value
  emit('focus-route', value || null)
}

</script>

<template>
  <article
    :class="[
      'graph-question-node',
      {
        'graph-question-node--current': data.canAnswer,
        'graph-question-node--historical': !data.canAnswer,
        'graph-question-node--shared': data.isShared,
        'graph-question-node--selected': selected === true,
        'graph-question-node--detailed': !data.canAnswer && showFullHistory,
      },
    ]"
    data-test="graph-question-node"
    data-layout-role="graph-node"
    :data-node-id="data.node.id"
  >

    <!-- Adaptive edge anchors: one source + one target handle per side.
         Source handles accept manual drag-connections; target handles accept
         incoming ones. Invisible until node hover (style.css). -->
    <Handle
      v-for="anchor in SOURCE_ANCHORS"
      :key="anchor.id"
      :id="anchor.id"
      type="source"
      :position="anchor.position"
      class="graph-question-node__handle graph-question-node__handle--source"
      :connectable="true"
      :connectable-start="true"
      :connectable-end="true"
      aria-hidden="true"
    />
    <Handle
      v-for="anchor in TARGET_ANCHORS"
      :key="anchor.id"
      :id="anchor.id"
      type="target"
      :position="anchor.position"
      class="graph-question-node__handle graph-question-node__handle--target"
      :connectable="true"
      :connectable-start="false"
      :connectable-end="true"
      aria-hidden="true"
    />
    <header class="graph-question-node__header" data-test="node-drag-handle" title="拖动标题栏移动节点">
      <span class="graph-question-node__identity">
        <span v-if="data.qLabel" class="graph-question-node__q-label">
          {{ data.qLabel }}
        </span>
        <span v-if="data.isLatest" class="graph-question-node__latest" data-test="latest-marker">
          最新
        </span>
      </span>
      <!-- 历史共享节点：header 只展示计数，不逐个渲染路线 chip；
           完整成员在阅读路线下拉框与 Inspector 路线归属中查看。
           当前可回答节点保持原有上下文 chip。 -->
      <span
        v-if="!data.canAnswer && data.isShared && data.routeMembership?.length"
        class="graph-question-node__routes graph-question-node__routes--compact"
        :title="data.routeMembership.map((membership) => membership.label).join(' · ')"
        data-test="shared-membership"
      >
        共享 · {{ data.routeMembership.length }} 条路线
      </span>
      <span
        v-else-if="data.routeMembership?.length"
        class="graph-question-node__routes"
        :title="data.routeMembership.map((membership) => membership.label).join(' · ')"
        data-test="route-membership"
      >
        <span
          v-for="membership in data.routeMembership"
          :key="membership.routeId"
          class="graph-route-chip"
          :class="{ 'graph-route-chip--active': membership.isActive }"
        >
          {{ membership.label }}
        </span>
      </span>
      <span
        class="badge graph-question-node__state"
        :class="presentationState.className"
        data-test="node-state"
      >
        {{ presentationState.label }}
      </span>
    </header>

    <div class="graph-question-node__body nodrag" data-test="node-body">
      <div
        v-if="data.isShared"
        class="graph-reading-route"
        data-test="shared-reading-route"
        @click.stop
      >
        <span class="graph-reading-route__label">当前查看</span>
        <span
          v-if="!needsReadingRouteChoice"
          class="graph-reading-route__value"
          data-test="reading-route-resolved"
          :title="readingRouteLabel ?? '未选择'"
        >{{ readingRouteLabel ?? '未选择' }}</span>
        <select
          v-else
          :id="'shared-reading-route-select-' + data.visualNodeKey"
          class="graph-reading-route__select nodrag"
          data-test="reading-route-select"
          :value="data.readingRouteId ?? ''"
          aria-label="当前查看路线"
          @change="setReadingRoute"
        >
          <option value="">未选择</option>
          <option
            v-for="membership in readingRouteOptions"
            :key="membership.routeId"
            :value="membership.routeId"
          >
            {{ membership.label }}
          </option>
        </select>
      </div>

      <!-- Virtual AgentRun projection; it is replaced by a real node after the
           Runtime persists the validated result. -->
      <template v-if="isPendingCard">
        <div class="graph-runtime-state" data-test="pending-card">
          <p class="graph-node-question">{{ node.question }}</p>
          <p class="graph-runtime-copy">{{ phaseToCopy(data.runtimePhase) }}</p>
          <p v-if="data.runtimeMessage" class="graph-runtime-error">{{ data.runtimeMessage }}</p>
          <button
            v-if="data.runtimeStatus === 'FAILED'"
            class="btn btn-primary graph-action nodrag"
            data-test="retry-pending"
            @click.stop="emit('retry-pending')"
          >
            重试
          </button>
        </div>
      </template>

      <!-- Current answerable node: direct answer interaction -->
      <template v-else-if="data.canAnswer">
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

      <!-- 选中的历史节点：完整问答（与当前问题节点同规格）。点击节点即展开，
           取消选择恢复紧凑导航卡。逐路线历史仍在 Inspector 中查看。 -->
      <template v-else-if="showFullHistory">
        <h3 class="graph-node-question" data-test="historical-question">
          {{ node.question }}
        </h3>
        <p v-if="node.purpose" class="graph-node-purpose">
          {{ node.purpose }}
        </p>

        <div v-if="primary" class="graph-history-answer" data-test="historical-answer">
          <p class="graph-history-answer__head">
            <span class="badge badge-confirmed">回答</span>
            <span v-if="primary.routeLabel" class="graph-history-answer__route">
              {{ primary.routeLabel }}
            </span>
          </p>
          <p
            v-if="primary.selectedOptionLabel"
            class="graph-history-answer__choice"
            data-test="historical-answer-option"
          >
            {{ primary.selectedOptionLabel }}
          </p>
          <RichAssistantText
            v-if="primary.freeText"
            class="graph-history-answer__text"
            data-test="historical-answer-text"
            :content="primary.freeText"
          />
          <p v-if="primary.inherited" class="meta-text">该回答继承自来源路线</p>
        </div>

        <!-- 阅读路线 tip 的 canonical 未答 Question：显示等待 + 激活所属路线
             即可回答（route count 不变，不创建 RESUME 分支）。 -->
        <div
          v-else-if="unansweredReadingTip"
          class="graph-answer-summary"
          data-test="waiting-summary"
        >
          <span class="badge badge-warn">{{ unansweredReadingTipLabel }} · 等待回答</span>
          <p v-if="node.purpose" class="graph-node-purpose">{{ node.purpose }}</p>
          <button
            v-if="data.readingRouteId"
            class="btn btn-primary graph-wake-answer nodrag"
            type="button"
            data-test="answer-this-question"
            @click.stop="emit('activate-route', data.readingRouteId)"
          >
            回答这个问题
          </button>
        </div>

        <p v-else class="meta-text" data-test="waiting-plain">等待回答</p>

        <!-- 完整选项列表：只读，标出实际提交的那一项。 -->
        <ul v-if="historyOptions.length > 0" class="graph-history-options" data-test="historical-options">
          <li
            v-for="entry in historyOptions"
            :key="entry.option.id"
            class="graph-history-option"
            :class="{ 'graph-history-option--chosen': entry.chosen }"
          >
            <span class="graph-history-option__mark" aria-hidden="true">{{ entry.chosen ? '✓' : '·' }}</span>
            <span class="graph-option-label">{{ entry.option.label }}</span>
            <span v-if="entry.option.impact" class="graph-option-impact">{{ entry.option.impact }}</span>
          </li>
        </ul>
      </template>

      <!-- 未选中的历史节点：紧凑导航卡；点击（选中）后展开完整信息。 -->
      <template v-else>
        <h4 class="graph-node-question graph-node-question--compact" data-test="historical-question">
          {{ node.question }}
        </h4>
        <p v-if="node.purpose" class="graph-node-purpose graph-node-purpose--compact">
          {{ node.purpose }}
        </p>

        <!-- 阅读路线 tip 的 canonical 未答 Question：显示等待 + 激活所属路线
             即可回答（route count 不变，不创建 RESUME 分支）。已答节点与未选
             查看路线的 shared 节点绝不出现此入口。 -->
        <div
          v-if="!primary && unansweredReadingTip"
          class="graph-answer-summary"
          data-test="waiting-summary"
        >
          <span class="badge badge-warn">{{ unansweredReadingTipLabel }} · 等待回答</span>
          <h4 class="graph-node-question graph-node-question--compact" data-test="waiting-question">
            {{ node.question }}
          </h4>
          <p v-if="node.purpose" class="graph-node-purpose">{{ node.purpose }}</p>
          <button
            v-if="data.readingRouteId"
            class="btn btn-primary graph-wake-answer nodrag"
            type="button"
            data-test="answer-this-question"
            @click.stop="emit('activate-route', data.readingRouteId)"
          >
            回答这个问题
          </button>
        </div>

        <!-- 其余历史节点：已答节点保持紧凑（完整答案在 Inspector 中查看）；
             未答节点明确显示等待即可，绝不按路线拆分出 route-specific
             的"回答这个问题"入口（那会人为制造 Shared 分叉）。 -->
        <p
          v-else-if="!primary"
          class="meta-text graph-node-question--compact"
          data-test="waiting-plain"
        >等待回答</p>
        <p v-else class="meta-text graph-node-expand-hint" data-test="expand-hint">点击查看完整回答</p>
      </template>
    </div>

    <!-- 操作轨道：悬浮在节点右侧外缘竖排（不在卡片内占位，left:100%），
         悬停或键盘聚焦节点时出现。仅历史节点提供；当前节点直接在卡片内作答。 -->
    <div
      v-if="!isPendingCard && !data.canAnswer"
      class="graph-node-actions graph-node-actions--toolbar"
      tabindex="0"
      role="toolbar"
      aria-label="节点操作"
    >
      <button
        class="btn graph-action nodrag"
        data-test="fork-node"
        title="我接受现在，换未来"
        @click.stop="forkNode"
      >
        从这里开新路线
      </button>
      <button
        class="btn graph-action nodrag"
        data-test="reanswer-node"
        title="问题没错，答案换一个"
        @click.stop="reanswerNode"
      >
        重新选择答案
      </button>
      <button
        class="btn graph-action nodrag"
        data-test="regenerate-node"
        :disabled="isRootNode || pending"
        title="问题本身换掉"
        @click.stop="regenerateNode"
      >
        换一个问题
      </button>
      <button
        class="btn graph-action nodrag"
        data-test="contextual-ai"
        title="在检查器中询问 AI"
        @click.stop="emit('contextual-ai', data.node.id)"
      >
        问 AI
      </button>
    </div>
  </article>
</template>
