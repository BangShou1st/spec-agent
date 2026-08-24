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
import { useWorkspaceStore } from '@/stores/workspaceStore'
import type { SpecAgentGraphNodeData } from '@/graph/graphProjection'

/**
 * Card for non-interaction workspace nodes (knowledge drafts, resource
 * references, artifacts). Registered by node kind in the canvas node-type
 * registry — new subtypes reuse this card instead of adding card classes.
 *
 * A user-authored draft stays editable in place while PROPOSED; confirmed
 * or agent-authored content is read-only and evolves through knowledge-state
 * transitions, revision, and branches — never silent rewrites.
 */
const ANCHOR_SIDES: Position[] = [Position.Left, Position.Right, Position.Top, Position.Bottom]
const SOURCE_ANCHORS = ANCHOR_SIDES.map((side) => ({ id: 'source-' + side, position: side }))
const TARGET_ANCHORS = ANCHOR_SIDES.map((side) => ({ id: 'target-' + side, position: side }))

const props = defineProps<{
  data: SpecAgentGraphNodeData
  selected?: boolean
}>()

const workspace = useWorkspaceStore()

const node = computed(() => props.data.node)
const isDraft = computed(() => node.value.userEditableDraft)
const contentText = computed(() => {
  const text = node.value.content?.text
  return typeof text === 'string' ? text : ''
})

const SUBTYPES = [
  { value: 'NOTE', label: '笔记' },
  { value: 'IDEA', label: '想法' },
  { value: 'REQUIREMENT', label: '需求' },
  { value: 'DECISION', label: '决策' },
  { value: 'RISK', label: '风险' },
  { value: 'ASSUMPTION', label: '假设' },
]
const subtypeLabel = computed(
  () => SUBTYPES.find((entry) => entry.value === node.value.subtype)?.label ?? node.value.subtype,
)
const knowledgeStatusLabel = computed(() => {
  switch (node.value.knowledgeStatus) {
    case 'PROPOSED': return '待确认'
    case 'CONFIRMED': return '已确认'
    case 'CHALLENGED': return '有质疑'
    case 'SUPERSEDED': return '已替代'
    default: return null
  }
})

// Draft editing state: local until saved; cancels restore the server value.
const editing = ref(false)
const editSubtype = ref(node.value.subtype)
const editText = ref(contentText.value)
watch(
  () => [node.value.id, node.value.subtype, contentText.value] as const,
  ([, subtype, text]) => {
    if (!editing.value) {
      editSubtype.value = subtype
      editText.value = text
    }
  },
  { immediate: true },
)

function startEditing(): void {
  editing.value = true
  editSubtype.value = node.value.subtype
  editText.value = contentText.value
}

function cancelEditing(): void {
  editing.value = false
  editSubtype.value = node.value.subtype
  editText.value = contentText.value
}

async function saveDraft(): Promise<void> {
  const ok = await workspace.reviseDraft(node.value.id, editSubtype.value, editText.value)
  if (ok) editing.value = false
}

async function continueFromHere(): Promise<void> {
  // Route context must be explicit; ambiguous shared nodes ask the user to
  // pick a reading route first (never active/first/latest fallback).
  const routeId = props.data.readingRouteId
  if (!routeId) return
  await workspace.continueFromNode(node.value.id, routeId)
}

async function confirmContent(): Promise<void> {
  await workspace.confirmKnowledge(node.value.id)
}
</script>

<template>
  <article
    class="graph-question-node graph-knowledge-node"
    :class="{ 'graph-question-node--shared': data.isShared }"
    data-test="graph-knowledge-node"
    data-layout-role="graph-node"
    :data-node-id="data.node.id"
  >
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
      <span class="graph-knowledge-node__kind badge" data-test="kind-badge">{{ subtypeLabel }}</span>
      <span v-if="knowledgeStatusLabel" class="graph-knowledge-node__status badge badge-open" data-test="knowledge-status">
        {{ knowledgeStatusLabel }}
      </span>
      <span v-if="data.isLatest" class="graph-question-node__latest" data-test="latest-marker">最新</span>
      <span class="graph-question-node__meta">
        <template v-if="data.isShared">共享 · {{ data.routeIds.length }} 条路线</template>
      </span>
    </header>

    <div class="graph-question-node__body nodrag" data-test="node-body">
      <!-- Editable draft: author content directly on the card. -->
      <template v-if="editing">
        <select v-model="editSubtype" class="graph-knowledge-node__subtype nodrag" data-test="draft-subtype" aria-label="类型">
          <option v-for="entry in SUBTYPES" :key="entry.value" :value="entry.value">{{ entry.label }}</option>
        </select>
        <textarea
          v-model="editText"
          class="graph-answer-input nodrag"
          data-test="draft-text"
          rows="4"
          placeholder="写下想法、需求或假设…"
          @click.stop
        ></textarea>
        <div class="graph-node-actions">
          <button
            class="btn btn-primary graph-action nodrag"
            data-test="save-draft"
            :disabled="workspace.graphCommandPending"
            @click.stop="saveDraft"
          >
            保存
          </button>
          <button class="btn graph-action nodrag" data-test="cancel-draft" @click.stop="cancelEditing">
            取消
          </button>
        </div>
      </template>

      <template v-else>
        <p v-if="contentText" class="graph-knowledge-node__text" data-test="knowledge-text">
          {{ contentText }}
        </p>
        <p v-else class="graph-knowledge-node__empty meta-text" data-test="knowledge-empty">
          空草稿
        </p>

        <div class="graph-node-actions graph-node-actions--toolbar" tabindex="0" role="toolbar" aria-label="节点操作">
          <button
            v-if="isDraft"
            class="btn graph-action nodrag"
            data-test="edit-draft"
            :disabled="workspace.graphCommandPending"
            @click.stop="startEditing"
          >
            编辑
          </button>
          <button
            v-if="isDraft && node.knowledgeStatus === 'PROPOSED' && contentText"
            class="btn graph-action nodrag"
            data-test="confirm-knowledge"
            :disabled="workspace.graphCommandPending"
            @click.stop="confirmContent"
          >
            确认内容
          </button>
          <button
            class="btn graph-action nodrag"
            data-test="continue-node"
            :disabled="!data.readingRouteId || workspace.graphCommandPending"
            :title="data.readingRouteId ? '从该节点继续探索（历史节点将创建探索分支）' : '共享节点请先在上方选择查看路线'"
            @click.stop="continueFromHere"
          >
            从这里继续
          </button>
        </div>
      </template>
    </div>
  </article>
</template>
