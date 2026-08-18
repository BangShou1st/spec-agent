<script setup lang="ts">
import { computed } from 'vue'
import type { SpecSnapshotResponse } from '@/api/types'
import SpecSnapshotList from './SpecSnapshotList.vue'

/**
 * 规格快照标签页。快照始终属于读取路线（readingRouteId，可能与当前路线
 * 不同）。生成始终针对后端当前路线（Active）并明确提示。快照永远标注为
 * 派生产物——不是权威来源。
 */
const props = defineProps<{
  routeId: string | null
  activeRouteId: string | null
  snapshots: SpecSnapshotResponse[]
  selectedSpecId: string | null
  generating: boolean
  commandPending: boolean
}>()

const emit = defineEmits<{
  'generate-spec': []
  select: [snapshotId: string]
}>()

const canGenerate = computed<boolean>(() => {
  return !props.generating && !props.commandPending && props.activeRouteId !== null
})

const readingIsActive = computed<boolean>(() => props.routeId === props.activeRouteId)

const selectedSpec = computed<SpecSnapshotResponse | null>(() => {
  if (props.selectedSpecId) {
    return props.snapshots.find((snapshot) => snapshot.id === props.selectedSpecId) ?? null
  }
  return [...props.snapshots].sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0] ?? null
})

function shortId(id: string): string {
  return id.slice(0, 8)
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString()
}

function truncated(value: string | null): string {
  return value ?? '—'
}
</script>

<template>
  <div class="spec-snapshot-panel" data-test="spec-panel">
    <div class="spec-generate-row">
      <button
        class="btn btn-primary"
        type="button"
        data-test="generate-spec"
        :disabled="!canGenerate"
        @click="emit('generate-spec')"
      >
        {{ generating ? '正在生成…' : '为当前路线生成规格' }}
      </button>
      <p v-if="generating" class="muted" style="font-size: 12px; margin: 0">正在生成…</p>
    </div>
    <p v-if="!activeRouteId" class="muted info-line" data-test="generate-spec-hint">
      没有当前路线——无法生成规格。
    </p>
    <p v-else class="muted info-line" data-test="active-route-target">
      当前路线：{{ shortId(activeRouteId) }}
    </p>
    <p
      v-if="!readingIsActive && routeId"
      class="info-line generate-warning"
      data-test="generate-focus-warning"
    >
      你目前正在查看 {{ shortId(routeId) }}，生成操作将针对当前路线 {{ shortId(activeRouteId ?? '') }}。
    </p>

    <div class="meta-text" style="margin-top: 8px">
      读取路线：{{ routeId ? shortId(routeId) : '—' }} 的规格快照
    </div>

    <SpecSnapshotList
      :snapshots="snapshots"
      :selected-id="selectedSpecId"
      @select="emit('select', $event)"
    />

    <div v-if="selectedSpec" class="snapshot-detail" data-test="spec-snapshot-detail">
      <div class="detail-head">
        <h3 style="margin: 0 0 4px">派生规格快照</h3>
        <span class="badge badge-open" data-test="derived-label">派生产物——不是权威来源</span>
        <span class="meta-text">创建于 {{ formatTime(selectedSpec.createdAt) }}</span>
      </div>

      <div class="provenance" data-test="spec-provenance">
        <div class="meta-text">快照：{{ selectedSpec.id }}</div>
        <div class="meta-text">路线：{{ selectedSpec.routeId }}</div>
        <div class="meta-text">tip node：{{ truncated(selectedSpec.tipNodeId) }}</div>
        <div class="meta-text">格式：{{ selectedSpec.format }}</div>
        <div class="meta-text">createdByRunId：{{ truncated(selectedSpec.createdByRunId) }}</div>
      </div>

      <section v-for="section in selectedSpec.sections" :key="section.id" class="spec-section" data-test="spec-section">
        <h4 style="margin: 0 0 4px">{{ section.title }}</h4>
        <p style="margin: 0; white-space: pre-wrap">{{ section.content }}</p>
      </section>

      <section v-if="selectedSpec.unresolvedItems.length > 0" class="spec-section">
        <h4 style="margin: 0 0 4px">未解决项</h4>
        <ul style="margin: 0; padding-left: 18px">
          <li v-for="(item, index) in selectedSpec.unresolvedItems" :key="index" data-test="unresolved-item">
            {{ item.text }}
          </li>
        </ul>
      </section>

      <section v-if="selectedSpec.sourceRefs.length > 0" class="spec-section">
        <h4 style="margin: 0 0 4px">来源引用</h4>
        <ul style="margin: 0; padding-left: 18px">
          <li v-for="(ref, index) in selectedSpec.sourceRefs" :key="index" data-test="source-reference">
            {{ ref.kind }}:{{ ref.refId }}
          </li>
        </ul>
      </section>
    </div>

    <p v-else-if="snapshots.length > 0" class="muted info-line">
      选择一条快照进行查看。
    </p>
  </div>
</template>

<style scoped>
.spec-generate-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.info-line {
  margin: 6px 0 0;
  font-size: 12px;
}

.generate-warning {
  color: var(--color-warn);
}

.snapshot-detail {
  margin-top: 12px;
  border-top: 1px solid var(--color-border);
  padding-top: 10px;
}

.detail-head {
  margin-bottom: 8px;
}

.provenance {
  background: var(--color-subdued);
  border-radius: var(--radius);
  padding: 8px;
  margin-bottom: 12px;
}

.spec-section {
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  padding: 10px;
  margin-bottom: 8px;
  font-size: 13px;
}
</style>
