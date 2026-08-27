<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { SpecAgentGraphNodeData } from '@/graph/graphProjection'
import { useWorkspaceStore } from '@/stores/workspaceStore'

/**
 * 节点详情检查器。只读展示节点内容（问题或通用工作区内容）、全部选项、
 * 每条路线的回答/等待状态、路线归属与语义关系。历史动作只向上发出意图；
 * 回答提交永远在 Graph 节点内，这里不提供第二套提交界面。
 *
 * 任意节点都可以发起上下文 AI 查询（"问 AI"）：查询使用该节点的 lineage
 * 与显式阅读路线作为上下文，回答不修改 Graph。
 */
const props = defineProps<{ data: SpecAgentGraphNodeData | null }>()

const emit = defineEmits<{
  fork: [nodeId: string]
  reanswer: [nodeId: string]
  regenerate: [nodeId: string]
}>()

const workspace = useWorkspaceStore()

const nodeQuestion = computed(() => props.data?.node.question || '')
const contentText = computed(() => {
  const text = props.data?.node.content?.text
  return typeof text === 'string' && text.trim() ? text : ''
})
const nodeTitle = computed(() => nodeQuestion.value || contentText.value || '（空草稿）')

const kindLabel = computed(() => {
  if (!props.data) return ''
  if (props.data.node.kind === 'INTERACTION') return '交互 · 提问'
  return `${props.data.node.kind} · ${props.data.node.subtype}`
})

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString()
}

function branchLabel(branchType: string | null | undefined): string {
  return {
    fork: '分支路线',
    reanswer: '重新选择答案',
    regenerate: '替代问题',
    continuation: '探索分支',
  }[branchType ?? ''] ?? branchType ?? ''
}

function membershipLabel(data: SpecAgentGraphNodeData, routeId: string): string {
  return data.routeMembership?.find((membership) => membership.routeId === routeId)?.label
    || data.routeStates.find((state) => state.routeId === routeId)?.routeLabel
    || '路线'
}

function orderedStates(data: SpecAgentGraphNodeData) {
  return [...data.routeStates].sort((left, right) => {
    if (left.routeId === data.readingRouteId) return -1
    if (right.routeId === data.readingRouteId) return 1
    return 0
  })
}

// ---- Contextual AI query -------------------------------------------------

const askInput = ref('')
const isVirtualPendingNode = computed(() => props.data?.node.id.startsWith('pending:') ?? false)
/**
 * The reading context is OPTIONAL for an AI query: a Floating node belongs to
 * no route (routeIds=[]) and still queries with routeId=null — the anchor
 * node itself is the minimum context. A route is a reading-context hint,
 * never an eligibility gate.
 */
const askRouteId = computed(() => {
  if (!props.data) return null
  if (props.data.readingRouteId) return props.data.readingRouteId
  return props.data.routeIds.length === 1 ? props.data.routeIds[0] : null
})
const askBlockedReason = computed(() => {
  if (!props.data) return null
  if (isVirtualPendingNode.value) return '运行中的临时卡片不能作为 AI 查询锚点'
  if (props.data.node.kind !== 'INTERACTION' && !contentText.value) return '先写下内容再询问 AI'
  return null
})

const queryResult = computed(() => {
  if (!props.data || !workspace.nodeQuery) return null
  const canonicalNodeId = props.data.canonicalNodeId ?? props.data.node.id
  return workspace.nodeQuery.nodeId === canonicalNodeId ? workspace.nodeQuery : null
})

watch(
  () => props.data?.node.id,
  () => {
    askInput.value = ''
  },
)

async function ask(): Promise<void> {
  if (!props.data || !askInput.value.trim()) return
  if (isVirtualPendingNode.value) return
  const canonicalNodeId = props.data.canonicalNodeId ?? props.data.node.id
  await workspace.askNodeAI(canonicalNodeId, askRouteId.value, askInput.value)
}

// ---- Semantic relations (view-only) ---------------------------------------
// 关系创建统一走 Canvas drag → Proposal → Confirm；Inspector 只负责查看
// canonical semantic relations（方向/类型）。这里不再提供第二套下拉创建器。

const relations = computed(() => {
  if (!props.data) return []
  const nodeId = props.data.node.id
  return (workspace.graphView?.relations ?? [])
    .filter((relation) => relation.sourceNodeId === nodeId || relation.targetNodeId === nodeId)
})

const relationTypeLabels: Record<string, string> = {
  RELATED_TO: '相关',
  DEPENDS_ON: '依赖',
  DERIVED_FROM: '派生自',
  CONFLICTS_WITH: '冲突',
  SUPPORTS: '支持',
}

function relationNodeLabel(nodeId: string): string {
  const node = workspace.graphView?.nodes.find((candidate) => candidate.id === nodeId)
  if (!node) return nodeId.slice(0, 8)
  if (node.question) return node.question.slice(0, 24)
  const text = node.content?.text
  return typeof text === 'string' && text ? text.slice(0, 24) : nodeId.slice(0, 8)
}

/** 对称关系（RELATED_TO/CONFLICTS_WITH）展示为无方向事实。 */
function relationDirectionLabel(relation: {
  relationType: string
  sourceNodeId: string
  targetNodeId: string
}): string {
  const nodeId = props.data?.node.id
  if (relation.relationType === 'RELATED_TO' || relation.relationType === 'CONFLICTS_WITH') {
    return `${relationNodeLabel(relation.sourceNodeId)} ↔ ${relationNodeLabel(relation.targetNodeId)}`
  }
  if (relation.sourceNodeId === nodeId) {
    return `→ ${relationNodeLabel(relation.targetNodeId)}`
  }
  return `← ${relationNodeLabel(relation.sourceNodeId)}`
}
</script>

<template>
  <div class="node-inspector" data-test="node-inspector">
    <template v-if="data">
      <h3 class="node-inspector__title" data-test="node-detail-question">{{ nodeTitle }}</h3>
      <p class="meta-text">
        <span class="badge badge-open" data-test="node-kind">{{ kindLabel }}</span>
        · 创建于 {{ formatTime(data.node.createdAt) }}
        <template v-if="data.node.authorKind === 'USER'">· 用户创建</template>
      </p>
      <p v-if="data.node.purpose" class="graph-node-purpose">{{ data.node.purpose }}</p>
      <p class="node-inspector__reading" data-test="current-reading-route">
        当前查看路线：<strong>{{ data.readingRouteId ? membershipLabel(data, data.readingRouteId) : '未选择' }}</strong>
      </p>

      <h4 class="node-inspector__heading">问 AI</h4>
      <div class="node-inspector__ask" data-test="node-ask">
        <textarea
          v-model="askInput"
          class="graph-answer-input node-inspector__ask-input"
          data-test="ask-input"
          rows="2"
          :placeholder="askBlockedReason ?? '例如：这个需求会影响哪些部分？'"
          :disabled="askBlockedReason !== null"
        ></textarea>
        <button
          class="btn btn-small btn-primary"
          data-test="ask-submit"
          :disabled="askBlockedReason !== null || !askInput.trim() || queryResult?.status === 'RUNNING'"
          @click="ask"
        >
          {{ queryResult?.status === 'RUNNING' ? '正在查询…' : '提问' }}
        </button>
        <p v-if="askBlockedReason" class="meta-text">{{ askBlockedReason }}</p>
        <div v-if="queryResult" class="node-inspector__answer" data-test="ask-result">
          <template v-if="queryResult.status === 'RUNNING'">
            <span class="badge badge-open">AI 正在基于该节点上下文回答…</span>
          </template>
          <template v-else-if="queryResult.status === 'COMPLETED' && queryResult.message">
            <p class="graph-answer-text">{{ queryResult.message }}</p>
          </template>
          <template v-else-if="queryResult.status === 'COMPLETED'">
            <span class="badge badge-warn">AI 未返回文字回答（可查看待确认提案）。</span>
          </template>
          <template v-else>
            <span class="badge badge-warn">查询失败，请稍后重试。</span>
          </template>
        </div>
      </div>

      <template v-if="data.node.options.length > 0">
        <h4 class="node-inspector__heading">选项</h4>
        <ul class="node-inspector__options">
          <li v-for="option in data.node.options" :key="option.id" class="node-inspector__option">
            <span class="graph-option-label">{{ option.label }}</span>
            <span v-if="option.impact" class="graph-option-impact">{{ option.impact }}</span>
          </li>
        </ul>
      </template>

      <h4 class="node-inspector__heading">路线归属</h4>
      <p class="meta-text">
        {{ data.routeIds.length }} 条路线
        <template v-if="data.isShared">（共享节点）</template>
      </p>
      <div v-if="data.routeMembership?.some((membership) => membership.branchType)" class="node-inspector__provenance">
        <h4 class="node-inspector__heading">分支来源</h4>
        <p
          v-for="membership in data.routeMembership?.filter((item) => item.branchType)"
          :key="membership.routeId"
          class="meta-text"
        >
          {{ membership.label }} · {{ branchLabel(membership.branchType) }}
          <template v-if="membership.sourceRouteId"> · 来源 {{ membershipLabel(data, membership.sourceRouteId) }}</template>
        </p>
      </div>

      <template v-if="data.node.kind === 'INTERACTION'">
        <h4 class="node-inspector__heading">各路线回答</h4>
        <div v-if="data.routeStates.length > 0" class="node-inspector__answers">
          <div
            v-for="state in orderedStates(data)"
            :key="state.routeId"
            class="graph-route-answer"
            :class="{ 'graph-route-answer--primary': state.answer?.isPrimary }"
            :data-test="`route-answer-${state.routeId}`"
          >
            <span class="meta-text">{{ state.routeLabel || membershipLabel(data, state.routeId) }}</span>
            <template v-if="state.answer">
              <span v-if="state.answer.selectedOptionLabel" class="badge badge-open">{{ state.answer.selectedOptionLabel }}</span>
              <p v-if="state.answer.freeText" class="graph-answer-text">{{ state.answer.freeText }}</p>
            </template>
            <span v-else class="badge badge-warn" data-test="route-waiting">等待回答</span>
          </div>
        </div>
        <p v-else class="muted" data-test="node-detail-no-answers">该节点所属路线都还没有回答。</p>
      </template>

      <h4 class="node-inspector__heading">语义关系</h4>
      <p v-if="relations.length === 0" class="muted" data-test="node-detail-no-relations">暂无语义关系。</p>
      <ul v-else class="node-inspector__relations" data-test="node-relations">
        <li v-for="relation in relations" :key="relation.id" class="meta-text">
          <span class="badge badge-open">{{ relationTypeLabels[relation.relationType] ?? relation.relationType }}</span>
          {{ relationDirectionLabel(relation) }}
          <span v-if="relation.origin === 'AGENT'" class="meta-text">（AI 建议）</span>
        </li>
      </ul>

      <!-- 历史节点才提供 Fork / Regenerate；当前待回答节点保持只读详情，
           回答只发生在 Graph 节点内部。 -->
      <div v-if="!data.canAnswer && data.node.kind === 'INTERACTION'" class="node-inspector__actions">
        <button class="btn btn-small" data-test="inspector-fork" @click="emit('fork', data.node.id)">从这里开新路线</button>
        <button class="btn btn-small" data-test="inspector-reanswer" @click="emit('reanswer', data.node.id)">重新选择答案</button>
        <button
          class="btn btn-small"
          data-test="inspector-regenerate"
          :disabled="data.node.parentNodeId === null"
          @click="emit('regenerate', data.node.id)"
        >
          换一个问题
        </button>
      </div>
    </template>
    <p v-else class="muted" data-test="node-detail-empty">选择一个节点查看详情。</p>
  </div>
</template>

<style scoped>
.node-inspector__title {
  margin: 0 0 6px;
  font-size: 15px;
}

.node-inspector__heading {
  margin: 14px 0 6px;
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--color-text-secondary);
}

.node-inspector__reading {
  margin: 10px 0;
  padding: 8px;
  border-radius: var(--radius);
  background: var(--color-accent-soft);
  font-size: 12px;
}

.node-inspector__ask {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.node-inspector__ask-input {
  min-height: 44px;
}

.node-inspector__answer {
  padding: 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-bg-inset, rgba(127, 127, 127, 0.06));
}

.node-inspector__options {
  list-style: none;
  margin: 0;
  padding: 0;
}

.node-inspector__option {
  padding: 6px 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  margin-bottom: 4px;
}

.node-inspector__answers {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.node-inspector__relations {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.node-inspector__actions {
  display: flex;
  gap: 6px;
  margin-top: 14px;
}

.node-inspector__relation-create {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin-top: 8px;
  padding: 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
}
</style>
