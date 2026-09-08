<script setup lang="ts">
import { computed, ref } from 'vue'
import type { SpecSnapshotResponse } from '@/api/types'

/**
 * Graph 中央 Spec Dock：默认折叠（约 48px），展开约占中央区 40%（上限 45%）。
 * Graph 始终挂载可见；Dock 只发 generate-spec / select-snapshot 意图，
 * 绝不改变 Focus / Active 路线。
 */
const props = defineProps<{
  readingRouteId: string | null
  readingRouteLabel: string
  activeRouteId: string | null
  activeRouteLabel: string
  snapshots: SpecSnapshotResponse[]
  selectedSpecId: string | null
  generating: boolean
  commandPending: boolean
}>()

const emit = defineEmits<{
  'generate-spec': []
  'select-snapshot': [snapshotId: string]
  'expanded-change': [expanded: boolean]
}>()

const expanded = ref(false)
const provenanceOpen = ref(false)

const sortedSnapshots = computed(() =>
  [...props.snapshots].sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
)

const selectedSpec = computed<SpecSnapshotResponse | null>(() => {
  if (props.selectedSpecId) {
    return props.snapshots.find((snapshot) => snapshot.id === props.selectedSpecId) ?? null
  }
  return sortedSnapshots.value[0] ?? null
})

const sectionCount = computed(() => selectedSpec.value?.sections.length ?? 0)
const unresolvedCount = computed(() => selectedSpec.value?.unresolvedItems.length ?? 0)
const emptyBody = computed(() => selectedSpec.value === null)
const latestLabel = computed(() => {
  if (props.snapshots.length === 0) return '暂无快照'
  if (selectedSpec.value && sortedSnapshots.value[0]?.id === selectedSpec.value.id) return '最新'
  return '历史版本'
})

const readingIsActive = computed(() => props.readingRouteId === props.activeRouteId)

const canGenerate = computed(() =>
  !props.generating && !props.commandPending && props.activeRouteId !== null,
)

const displaySourceRefs = computed(() => {
  const seen = new Set<string>()
  return (selectedSpec.value?.sourceRefs ?? []).filter((ref) => {
    const key = `${ref.kind}:${ref.refId}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
})

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString()
}

function toggle(): void {
  expanded.value = !expanded.value
  emit('expanded-change', expanded.value)
}

function onSelectSnapshot(event: Event): void {
  const value = (event.target as HTMLSelectElement).value
  if (value) emit('select-snapshot', value)
}
</script>

<template>
  <section
    class="spec-dock"
    :class="[
      expanded ? 'spec-dock--expanded' : 'spec-dock--collapsed',
      { 'spec-dock--empty': expanded && emptyBody },
    ]"
    :data-state="expanded ? 'expanded' : 'collapsed'"
    data-test="spec-dock"
    aria-label="规格停靠栏"
  >
    <div class="spec-dock__bar">
      <button
        class="spec-dock__toggle"
        type="button"
        data-test="spec-dock-toggle"
        :aria-expanded="expanded ? 'true' : 'false'"
        aria-label="展开或折叠规格"
        @click="toggle"
      >
        <span class="spec-dock__chevron" aria-hidden="true">{{ expanded ? '▾' : '▴' }}</span>
      </button>
      <!-- 摘要文本保持可读；键盘切换走唯一的 toggle button，避免重复停靠点。 -->
      <div
        class="spec-dock__summary"
        data-test="spec-dock-summary"
        @click="toggle"
      >
        <strong>规格</strong>
        <span class="spec-dock__meta">{{ readingRouteLabel }} · {{ latestLabel }}</span>
        <span v-if="selectedSpec" class="spec-dock__meta">
          {{ sectionCount }} 个章节 · {{ unresolvedCount }} 个未解决项
        </span>
        <span v-else class="spec-dock__meta">暂无快照</span>
        <span
          v-if="!readingIsActive && readingRouteId"
          class="spec-dock__meta spec-dock__meta--warn"
          data-test="spec-route-warning"
        >
          正在查看 {{ readingRouteLabel }}，生成目标 {{ activeRouteLabel }}
        </span>
      </div>
    </div>

    <div v-if="expanded" class="spec-dock__body" data-test="spec-dock-body">
      <div class="spec-dock__head">
        <div class="spec-dock__routes">
          <span>正在查看：<strong>{{ readingRouteLabel }}</strong></span>
          <span>生成目标：<strong>{{ activeRouteLabel }}</strong></span>
        </div>
        <p
          v-if="!readingIsActive && readingRouteId"
          class="spec-dock__warning"
          data-test="spec-route-warning"
        >
          你目前正在查看 {{ readingRouteLabel }}，生成操作将针对当前路线 {{ activeRouteLabel }}。
        </p>
        <div class="spec-dock__actions">
          <button
            class="btn btn-primary btn-small"
            type="button"
            data-test="generate-spec"
            :disabled="!canGenerate"
            @click="emit('generate-spec')"
          >
            {{ generating ? '正在生成…' : '为当前路线生成规格' }}
          </button>
          <label v-if="sortedSnapshots.length > 0" class="spec-dock__history">
            快照
            <select
              data-test="spec-snapshot-select"
              :value="selectedSpec?.id ?? ''"
              aria-label="选择规格快照"
              @change="onSelectSnapshot"
            >
              <option
                v-for="(snapshot, index) in sortedSnapshots"
                :key="snapshot.id"
                :value="snapshot.id"
              >
                {{ index === 0 ? '最新 · ' : '' }}{{ formatTime(snapshot.createdAt) }}
              </option>
            </select>
          </label>
        </div>
        <p v-if="!activeRouteId" class="muted">没有当前路线——无法生成规格。</p>
      </div>

      <div v-if="selectedSpec" class="spec-dock__detail" data-test="spec-snapshot-detail">
        <div class="spec-dock__detail-head">
          <span class="badge badge-open" data-test="derived-label">派生产物——不是权威来源</span>
          <span class="meta-text">创建于 {{ formatTime(selectedSpec.createdAt) }}</span>
        </div>

        <section
          v-for="section in selectedSpec.sections"
          :key="section.id"
          class="spec-dock__section"
          data-test="spec-section"
        >
          <h4>{{ section.title }}</h4>
          <p>{{ section.content }}</p>
        </section>

        <section v-if="selectedSpec.unresolvedItems.length > 0" class="spec-dock__section">
          <h4>未解决项</h4>
          <ul>
            <li v-for="(item, index) in selectedSpec.unresolvedItems" :key="index" data-test="unresolved-item">
              {{ item.text }}
            </li>
          </ul>
        </section>

        <div v-if="selectedSpec.sourceRefs.length > 0" class="spec-dock__provenance">
          <button
            class="btn btn-small"
            type="button"
            data-test="spec-provenance-toggle"
            :aria-expanded="provenanceOpen ? 'true' : 'false'"
            @click="provenanceOpen = !provenanceOpen"
          >
            {{ provenanceOpen ? '收起来源与追溯' : '来源与追溯' }}
          </button>
          <ul v-if="provenanceOpen" data-test="spec-provenance-detail">
            <li v-for="ref in displaySourceRefs" :key="ref.kind + ':' + ref.refId" data-test="source-reference">
              {{ ref.kind }}：{{ ref.refId }}
            </li>
          </ul>
        </div>
      </div>
      <p v-else class="muted">该路线还没有规格快照。</p>
    </div>
  </section>
</template>

<style scoped>
.spec-dock {
  border-top: 1px solid var(--color-border);
  /* A whisper of tint helps the Dock read as a graph-derived deliverable
     region without ever competing with the Graph for attention. */
  background: #fbfbfe;
}

.spec-dock--collapsed {
  height: 48px;
  min-height: 48px;
  max-height: 48px;
  overflow: hidden;
}

.spec-dock--expanded {
  flex: 0 0 40%;
  max-height: 45%;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

/* Without a snapshot the expanded body has almost nothing to show: collapse
   the Dock to its real content height so the Graph keeps the space instead
   of a large empty slab. */
.spec-dock--expanded.spec-dock--empty {
  flex: 0 0 auto;
}

.spec-dock__bar {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 48px;
  min-height: 48px;
  padding: 0 12px;
  transition: background-color 120ms ease;
}

.spec-dock__bar:hover {
  background: var(--color-surface-subtle);
}

@media (prefers-reduced-motion: reduce) {
  .spec-dock__bar {
    transition: none;
  }
}

.spec-dock__toggle {
  border: 0;
  background: transparent;
  cursor: pointer;
  font-size: 14px;
  color: var(--color-text-secondary);
  padding: 4px;
}

.spec-dock__summary {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  cursor: pointer;
  font-size: 13px;
}

.spec-dock__meta {
  font-size: 12px;
  color: var(--color-text-secondary);
  white-space: nowrap;
}

.spec-dock__meta--warn {
  color: var(--color-warn);
}

.spec-dock__body {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 0 12px 12px;
  border-top: 1px solid var(--color-border);
}

.spec-dock__head {
  padding-top: 8px;
}

.spec-dock__routes {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: var(--color-text-secondary);
}

.spec-dock__warning {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--color-warn);
}

.spec-dock__actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
  flex-wrap: wrap;
}

.spec-dock__history {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}

.spec-dock__detail {
  margin-top: 8px;
}

.spec-dock__detail-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.spec-dock__section {
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  padding: 10px;
  margin-bottom: 8px;
  font-size: 13px;
}

.spec-dock__section h4 {
  margin: 0 0 6px;
  font-size: 13px;
  font-weight: 650;
  line-height: 1.45;
}

.spec-dock__section p {
  margin: 0;
  line-height: 1.6;
  white-space: pre-wrap;
}

.spec-dock__provenance {
  margin-top: 8px;
}
</style>
