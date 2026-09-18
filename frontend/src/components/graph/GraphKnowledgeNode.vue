<script lang="ts">
// Registered through the options `components` block (not a script-setup
// import) so the template resolves <Handle> by name; unit tests can then
// stub it, while the real app renders Vue Flow's Handle as usual.
import { Handle } from '@vue-flow/core'
export default { components: { Handle } }
</script>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { Position } from '@vue-flow/core'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { useGraphUiStore } from '@/stores/graphUiStore'
import type { SpecAgentGraphNodeData } from '@/graph/graphProjection'
import RichAssistantText from '@/components/global-assistant/RichAssistantText.vue'
import FilePreviewDialog from '@/components/FilePreviewDialog.vue'
import SkillSlashMenu from '@/components/graph/SkillSlashMenu.vue'
import { useSkillSlashPicker } from '@/composables/useSkillSlashPicker'
import type { SkillSummary } from '@/api/skillTypes'

/**
 * Card for non-interaction workspace nodes (knowledge drafts, resource
 * references, artifacts). Registered by node kind in the canvas node-type
 * registry — new subtypes reuse this card instead of adding card classes.
 *
 * A user-authored draft stays editable in place while PROPOSED; confirmed
 * or agent-authored content is read-only and evolves through knowledge-state
 * transitions, revision, and branches — never silent rewrites. Double-click
 * (or a pending edit request right after creation) opens the editor.
 */
const ANCHOR_SIDES: Position[] = [Position.Left, Position.Right, Position.Top, Position.Bottom]
const SOURCE_ANCHORS = ANCHOR_SIDES.map((side) => ({ id: 'source-' + side, position: side }))
const TARGET_ANCHORS = ANCHOR_SIDES.map((side) => ({ id: 'target-' + side, position: side }))

const props = defineProps<{
  data: SpecAgentGraphNodeData
  selected?: boolean
}>()

const workspace = useWorkspaceStore()
const graphUi = useGraphUiStore()

const emit = defineEmits<{
  'contextual-ai': [nodeId: string]
}>()

const node = computed(() => props.data.node)
const isDraft = computed(() => node.value.userEditableDraft)
const contentText = computed(() => {
  const text = node.value.content?.text
  return typeof text === 'string' ? text : ''
})

/** 文件资源（subtype = FILE）：卡片只显示图标+文件名+上传时间，正文进弹窗。 */
const isFileResource = computed(() => node.value.kind === 'RESOURCE' && node.value.subtype === 'FILE')
const fileName = computed(() => {
  const name = node.value.content?.fileName
  return typeof name === 'string' && name.length > 0 ? name : null
})
const fileDataUrl = computed(() => {
  const url = node.value.content?.fileDataUrl
  return typeof url === 'string' && url.length > 0 ? url : null
})
const fileIconKind = computed(() => {
  const name = fileName.value ?? ''
  const lower = name.toLowerCase()
  if (lower.endsWith('.pdf')) return 'pdf'
  if (lower.endsWith('.docx') || lower.endsWith('.doc')) return 'word'
  if (lower.endsWith('.xlsx') || lower.endsWith('.xlsm') || lower.endsWith('.csv')) return 'excel'
  if (lower.endsWith('.png') || lower.endsWith('.jpg') || lower.endsWith('.jpeg')
    || lower.endsWith('.webp') || lower.endsWith('.bmp') || lower.endsWith('.gif')) return 'image'
  return 'text'
})
/** 上传时间：节点创建时间即资源添加时间。 */
const uploadedAt = computed(() => {
  const iso = node.value.createdAt
  if (!iso) return null
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return null
  const pad = (n: number): string => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
})
const previewOpen = ref(false)
function openPreview(): void {
  if (isFileResource.value) previewOpen.value = true
}

const SUBTYPES = [
  { value: 'NOTE', label: '笔记' },
  { value: 'IDEA', label: '想法' },
  { value: 'REQUIREMENT', label: '需求' },
  { value: 'DECISION', label: '决策' },
  { value: 'RISK', label: '风险' },
  { value: 'ASSUMPTION', label: '假设' },
]
/** 资源子类型（kind = RESOURCE）与知识子类型共用这一张卡，标签各自成表。 */
const RESOURCE_SUBTYPES = [
  { value: 'FILE', label: '文档' },
  { value: 'TEXT', label: '文本资源' },
  { value: 'URL', label: '链接' },
  { value: 'IMAGE', label: '图片' },
  { value: 'REPOSITORY', label: '代码库' },
  { value: 'API_DOCUMENTATION', label: '接口文档' },
]
const subtypeLabel = computed(() => {
  const subtype = node.value.subtype
  const entry = [...SUBTYPES, ...RESOURCE_SUBTYPES].find((candidate) => candidate.value === subtype)
  return entry?.label ?? subtype
})

/**
 * 浮动节点（不属于任何路线）：资源与草稿都可以先独立存在，之后由用户
 * 把连线拖到路线末端来接入。卡片必须明说这一点，否则"资源去哪了/怎么进路线"
 * 只能靠猜。
 */
const isFloating = computed(() => (props.data.routeIds?.length ?? 0) === 0)

/** 能断开为独立节点的前提：它就是当前阅读路线的末端。 */
const canDetach = computed(() =>
  props.data.isTipOfReadingRoute === true && props.data.readingRouteId !== null,
)

async function detachFromRoute(): Promise<void> {
  const routeId = props.data.readingRouteId
  if (!routeId) return
  await workspace.disconnectNode(node.value.id, routeId)
}
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
// "/" picker: binds the picked skill for persistence; the visible mention
// lives in the text itself, the authoritative binding rides in content.
const picker = useSkillSlashPicker()
const draftInputEl = ref<HTMLTextAreaElement | null>(null)
const editSkillId = ref<string | null>(null)
const editSkillName = computed(() => {
  if (!editSkillId.value) return null
  return picker.enabledSkills.value.find((skill) => skill.skillId === editSkillId.value)?.name
    ?? editSkillId.value
})
function boundSkillId(content: unknown): string | null {
  const value = (content as Record<string, unknown> | undefined)?.skillId
  return typeof value === 'string' && value.trim().length > 0 ? value : null
}
watch(
  () => [node.value.id, node.value.subtype, contentText.value] as const,
  ([, subtype, text]) => {
    if (!editing.value) {
      editSubtype.value = subtype
      editText.value = text
      editSkillId.value = boundSkillId(node.value.content)
    }
  },
  { immediate: true },
)

function startEditing(): void {
  editing.value = true
  editSubtype.value = node.value.subtype
  editText.value = contentText.value
  editSkillId.value = boundSkillId(node.value.content)
  void nextTick(updateMenuPosition)
}

/** 双击卡片直接进入编辑（仅限用户可编辑草稿）。 */
function onCardDblClick(): void {
  if (isDraft.value && !editing.value) startEditing()
}

// 创建后自动进入编辑：workspace 请求编辑该视觉节点时消费一次请求。
watch(
  () => graphUi.pendingEditNodeKey,
  () => {
    const key = props.data.visualNodeKey
    if (key && graphUi.consumeNodeEditRequest(key)) {
      startEditing()
    }
  },
  { immediate: true },
)

function cancelEditing(): void {
  editing.value = false
  editSubtype.value = node.value.subtype
  editText.value = contentText.value
  editSkillId.value = boundSkillId(node.value.content)
  picker.close()
}

async function saveDraft(): Promise<void> {
  const ok = await workspace.reviseDraft(node.value.id, editSubtype.value, editText.value, editSkillId.value)
  if (ok) {
    editing.value = false
    picker.close()
  }
}

/** "/" 输入检测：光标前的 "/query" 词元打开已启用 Skill 候选菜单。 */
function onDraftInput(event: Event): void {
  const el = event.target as HTMLTextAreaElement
  picker.syncWithCaret(editText.value, el.selectionStart ?? 0)
  if (picker.open.value) updateMenuPosition()
}

function onDraftKeydown(event: KeyboardEvent): void {
  if (!picker.open.value) return
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    picker.move(1)
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    picker.move(-1)
  } else if (event.key === 'Enter') {
    const skill = picker.activeSkill()
    if (skill) {
      event.preventDefault()
      chooseSkill(skill)
    }
  } else if (event.key === 'Escape') {
    picker.close()
  }
}

function chooseSkill(skill: SkillSummary): void {
  const el = draftInputEl.value
  const caret = el?.selectionStart ?? editText.value.length
  const applied = picker.applySkill(editText.value, caret, skill)
  editText.value = applied.text
  editSkillId.value = skill.skillId
  picker.close()
  if (el) {
    void nextTick(() => el.setSelectionRange(applied.caret, applied.caret))
  }
}

// 菜单 Teleport 到 body 后用 fixed 定位贴住输入框：vue-flow 的节点卡片
// 又窄又处在其自身的层叠/拖拽上下文里，卡内定位会被其他节点盖住、点击
// 会跟节点拖拽抢事件——浮层脱离节点才能和节点交互互不干扰。
const menuPosition = ref({ top: 0, left: 0, width: 0 })
function updateMenuPosition(): void {
  const el = draftInputEl.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  const width = Math.max(rect.width, 260)
  const top = Math.min(rect.bottom + 4, window.innerHeight - 236)
  menuPosition.value = {
    top: Math.max(8, top),
    left: Math.min(Math.max(8, rect.left), window.innerWidth - width - 8),
    width,
  }
}

// blur 延迟关闭：点击菜单滚动条时 Chromium 也会对 textarea 触发 blur，
// 立即关闭会让滚动条永远拖不动。菜单内任何按下（press 事件）都豁免
// 掉这次关闭；真正的外部点击仍即时收起。
let blurCloseTimer: number | undefined
let lastMenuPressAt = 0
function onDraftBlur(): void {
  if (blurCloseTimer !== undefined) window.clearTimeout(blurCloseTimer)
  blurCloseTimer = window.setTimeout(() => {
    blurCloseTimer = undefined
    if (Date.now() - lastMenuPressAt < 400) return
    picker.close()
  }, 150)
}
function onMenuPress(): void {
  lastMenuPressAt = Date.now()
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
    :class="{
      'graph-question-node--shared': data.isShared,
      'graph-question-node--selected': selected === true,
    }"
    data-test="graph-knowledge-node"
    data-layout-role="graph-node"
    :data-node-id="data.node.id"
    @dblclick="onCardDblClick"
  >
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
      <span class="graph-knowledge-node__kind badge" data-test="kind-badge">{{ subtypeLabel }}</span>
      <span v-if="isFloating" class="graph-knowledge-node__floating badge badge-warn" data-test="floating-badge">
        独立节点
      </span>
      <span v-if="knowledgeStatusLabel" class="graph-knowledge-node__status badge badge-open" data-test="knowledge-status">
        {{ knowledgeStatusLabel }}
      </span>
      <span v-if="data.isLatest" class="graph-question-node__latest" data-test="latest-marker">最新</span>
      <span
        v-if="data.routeMembership?.length"
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
    </header>

    <div class="graph-question-node__body nodrag" data-test="node-body">
      <!-- 文件资源卡：图标+文件名+上传时间，点击查看原件（弹窗）。 -->
      <button
        v-if="isFileResource && !editing"
        type="button"
        class="graph-file-card nodrag"
        data-test="resource-file-card"
        :title="fileName ?? '文件资源'"
        @click.stop="openPreview"
      >
        <span class="graph-file-card__icon" :data-file-kind="fileIconKind" aria-hidden="true">
          <img v-if="fileIconKind === 'image' && fileDataUrl" :src="fileDataUrl" alt="" />
          <span v-else class="graph-file-card__icon-text">{{ fileIconKind.toUpperCase() }}</span>
        </span>
        <span class="graph-file-card__info">
          <span class="graph-file-card__name" data-test="resource-file-name">{{ fileName ?? '未命名文件' }}</span>
          <span v-if="uploadedAt" class="graph-file-card__time meta-text" data-test="resource-file-uploaded-at">上传于 {{ uploadedAt }}</span>
        </span>
      </button>

      <!-- Editable draft: author content directly on the card. -->
      <template v-if="editing">
        <select v-model="editSubtype" class="graph-knowledge-node__subtype nodrag" data-test="draft-subtype" aria-label="类型">
          <option v-for="entry in SUBTYPES" :key="entry.value" :value="entry.value">{{ entry.label }}</option>
        </select>
        <div class="graph-knowledge-node__editor nodrag">
          <textarea
            ref="draftInputEl"
            v-model="editText"
            class="graph-answer-input nodrag"
            data-test="draft-text"
            rows="4"
            placeholder="写下想法、需求或假设…（输入 / 可绑定 Skill）"
            @click.stop
            @input="onDraftInput"
            @keydown="onDraftKeydown"
            @blur="onDraftBlur"
          ></textarea>
        </div>
        <div v-if="editSkillId" class="graph-knowledge-node__skill-bind nodrag" data-test="bound-skill-chip">
          <span class="badge">Skill: {{ editSkillName ?? editSkillId }}</span>
          <button
            type="button"
            class="graph-knowledge-node__skill-unbind"
            data-test="unbind-skill"
            title="解除 Skill 绑定"
            @click.stop="editSkillId = null"
          >
            ×
          </button>
        </div>
        <div class="graph-node-actions">
          <button
            class="btn graph-action nodrag"
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

      <template v-else-if="!isFileResource">
        <!-- 与全局 AI 助手同一套富文本渲染：段落 / 列表 / 代码 / 引用都能正确
             换行与缩进，长笔记不再被压成一坨纯文本。 -->
        <RichAssistantText
          v-if="contentText"
          class="graph-knowledge-node__text"
          data-test="knowledge-text"
          :content="contentText"
        />
        <p v-else class="graph-knowledge-node__empty meta-text" data-test="knowledge-empty">
          空草稿
        </p>
      </template>

      <p v-if="isFloating" class="meta-text" data-test="floating-hint">
        还没接入任何路线：把卡片侧面的连线拖到一条路线的末端节点即可接入（AI 只有接入后才读得到它）
      </p>
    </div>

    <!-- 操作轨道：与问题节点一致，悬浮在节点右侧外缘竖排（left:100%），
         悬停或键盘聚焦节点时出现；编辑态使用卡片内的保存/取消表单按钮。 -->
    <div
      v-if="!editing"
      class="graph-node-actions graph-node-actions--toolbar"
      tabindex="0"
      role="toolbar"
      aria-label="节点操作"
    >
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
        v-if="!isFloating"
        class="btn graph-action nodrag"
        data-test="continue-node"
        :disabled="!data.readingRouteId || workspace.graphCommandPending"
        :title="data.readingRouteId ? '从该节点继续探索（历史节点将创建探索分支）' : '共享节点请先在上方选择查看路线'"
        @click.stop="continueFromHere"
      >
        从这里继续
      </button>
      <button
        v-if="canDetach"
        class="btn graph-action nodrag"
        data-test="detach-node"
        title="从当前路线断开，变成独立节点（内容保留）"
        :disabled="workspace.graphCommandPending"
        @click.stop="detachFromRoute"
      >
        断开路线
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

    <FilePreviewDialog
      :open="previewOpen"
      :file-name="fileName"
      :data-url="fileDataUrl"
      :kind="fileIconKind"
      :extracted-text="contentText"
      @close="previewOpen = false"
    />

    <!-- 浮层挂在 body 上（fixed 定位），脱离 vue-flow 节点的层叠与拖拽上下文 -->
    <Teleport to="body">
      <SkillSlashMenu
        v-if="picker.open.value"
        class="skill-slash-menu--floating nowheel"
        :style="{
          position: 'fixed',
          top: menuPosition.top + 'px',
          left: menuPosition.left + 'px',
          width: menuPosition.width + 'px',
        }"
        :items="picker.filtered.value"
        :active-index="picker.activeIndex.value"
        @select="chooseSkill"
        @hover="picker.activeIndex.value = $event"
        @press="onMenuPress"
      />
    </Teleport>
  </article>
</template>

<style scoped>
.graph-knowledge-node__editor {
  position: relative;
}

.graph-knowledge-node__skill-bind {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 0 8px;
}

.graph-knowledge-node__skill-unbind {
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
  padding: 2px 5px;
  border-radius: 4px;
  color: var(--color-text-secondary, #666);
}

.graph-knowledge-node__skill-unbind:hover {
  background: var(--color-surface-hover, #efeafd);
}
</style>
