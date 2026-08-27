<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'

/**
 * 真实 mouse drag（source handle → target handle）只产生 Pending Relation
 * Proposal。本组件是轻量确认器：显示 source/target、让用户选择 5 种关系
 * 类型、对 directional 类型明确显示方向并允许反转。Confirm 才调 backend；
 * Cancel / Esc / click-away 不产生任何持久化关系。
 */
const props = defineProps<{
  open: boolean
  sourceNodeId: string | null
  targetNodeId: string | null
  sourceLabel: string
  targetLabel: string
  pending: boolean
}>()

const emit = defineEmits<{
  confirm: [payload: {
    sourceNodeId: string
    targetNodeId: string
    relationType: string
  }]
  cancel: []
}>()

interface RelationTypeMeta {
  type: string
  label: string
  symmetric: boolean
  directionLabel: (source: string, target: string) => string
}

const RELATION_TYPES: RelationTypeMeta[] = [
  {
    type: 'RELATED_TO',
    label: '相关',
    symmetric: true,
    directionLabel: (s, t) => `${s} 与 ${t} 相关`,
  },
  {
    type: 'DEPENDS_ON',
    label: '依赖',
    symmetric: false,
    directionLabel: (s, t) => `${s} 依赖 ${t}`,
  },
  {
    type: 'DERIVED_FROM',
    label: '派生自',
    symmetric: false,
    directionLabel: (s, t) => `${s} 派生自 ${t}`,
  },
  {
    type: 'CONFLICTS_WITH',
    label: '冲突',
    symmetric: true,
    directionLabel: (s, t) => `${s} 与 ${t} 冲突`,
  },
  {
    type: 'SUPPORTS',
    label: '支持',
    symmetric: false,
    directionLabel: (s, t) => `${s} 支持 ${t}`,
  },
]

const selectedType = ref('RELATED_TO')
const reversed = ref(false)

watch(
  () => props.open,
  (open) => {
    if (open) {
      selectedType.value = 'RELATED_TO'
      reversed.value = false
      window.addEventListener('keydown', onDocumentKeydown)
    } else {
      window.removeEventListener('keydown', onDocumentKeydown)
    }
  },
)

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onDocumentKeydown)
})

function onDocumentKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    emit('cancel')
  }
}

const typeMeta = computed(() =>
  RELATION_TYPES.find((candidate) => candidate.type === selectedType.value)
    ?? RELATION_TYPES[0],
)

const effectiveSourceId = computed(() =>
  reversed.value ? props.targetNodeId : props.sourceNodeId,
)
const effectiveTargetId = computed(() =>
  reversed.value ? props.sourceNodeId : props.targetNodeId,
)
const effectiveSourceLabel = computed(() =>
  reversed.value ? props.targetLabel : props.sourceLabel,
)
const effectiveTargetLabel = computed(() =>
  reversed.value ? props.sourceLabel : props.targetLabel,
)

/** 对称类型（RELATED_TO/CONFLICTS_WITH）无方向概念，canonical 端点即事实。 */
const isSymmetric = computed(() => typeMeta.value.symmetric)
const directionCopy = computed(() => {
  if (!effectiveSourceLabel.value || !effectiveTargetLabel.value) {
    return ''
  }
  return typeMeta.value.directionLabel(effectiveSourceLabel.value, effectiveTargetLabel.value)
})

function chooseType(type: string): void {
  selectedType.value = type
}

function toggleReverse(): void {
  reversed.value = !reversed.value
}

function confirm(): void {
  if (!effectiveSourceId.value || !effectiveTargetId.value) return
  emit('confirm', {
    sourceNodeId: effectiveSourceId.value,
    targetNodeId: effectiveTargetId.value,
    relationType: selectedType.value,
  })
}

function handleKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    emit('cancel')
  }
}

function handleBackdrop(event: MouseEvent): void {
  if (event.target === event.currentTarget) {
    emit('cancel')
  }
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="relation-proposal-backdrop"
      data-test="relation-proposal"
      @mousedown="handleBackdrop"
      @keydown="handleKeydown"
      tabindex="-1"
    >
      <div
        class="relation-proposal-dialog"
        role="dialog"
        aria-label="确认语义关系"
        @mousedown.stop
      >
        <h3 class="relation-proposal-dialog__title">确认语义关系</h3>
        <p class="relation-proposal-dialog__endpoints" data-test="relation-endpoints">
          {{ directionCopy || '选择一个关系类型' }}
        </p>

        <div class="relation-proposal-dialog__types" data-test="relation-types">
          <button
            v-for="candidate in RELATION_TYPES"
            :key="candidate.type"
            class="btn relation-proposal-dialog__type"
            :class="{ 'relation-proposal-dialog__type--selected': candidate.type === selectedType }"
            :data-test="`relation-type-${candidate.type.toLowerCase()}`"
            @click="chooseType(candidate.type)"
          >
            {{ candidate.label }}
          </button>
        </div>

        <button
          v-if="!isSymmetric"
          class="btn btn-small relation-proposal-dialog__reverse"
          data-test="relation-reverse"
          @click="toggleReverse"
        >
          {{ reversed ? '↔ 使用原始方向' : '↔ 反转方向' }}
        </button>

        <div class="relation-proposal-dialog__actions">
          <button
            class="btn"
            data-test="relation-cancel"
            @click="emit('cancel')"
          >
            取消
          </button>
          <button
            class="btn btn-primary"
            data-test="relation-confirm"
            :disabled="pending || !effectiveSourceId || !effectiveTargetId"
            @click="confirm"
          >
            {{ pending ? '正在确认…' : '确认关系' }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.relation-proposal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 200;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(15, 15, 20, 0.35);
}

.relation-proposal-dialog {
  width: 340px;
  padding: 16px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-bg-card, #1b1b22);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.45);
}

.relation-proposal-dialog__title {
  margin: 0 0 8px;
  font-size: 15px;
}

.relation-proposal-dialog__endpoints {
  margin: 0 0 12px;
  padding: 8px;
  border-radius: var(--radius);
  background: var(--color-accent-soft);
  font-size: 13px;
  min-height: 34px;
}

.relation-proposal-dialog__types {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 10px;
}

.relation-proposal-dialog__type {
  flex: 1 0 30%;
  min-width: 90px;
}

.relation-proposal-dialog__type--selected {
  border-color: var(--color-accent, #6c8cff);
  background: var(--color-accent-soft);
}

.relation-proposal-dialog__reverse {
  margin-bottom: 12px;
}

.relation-proposal-dialog__actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>