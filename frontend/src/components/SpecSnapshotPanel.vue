<script setup lang="ts">
import { computed, watch } from 'vue'
import type { SpecSnapshotResponse } from '@/api/types'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import SpecSnapshotList from './SpecSnapshotList.vue'

/**
 * "Spec Snapshots" tab. Snapshots always belong to a SELECTED route (which may
 * differ from the active route). Generation targets the project's ACTIVE route
 * only and is disabled without a valid active route/tip. A snapshot is always
 * labeled as a derived artifact — never source of truth — and its content is
 * rendered faithfully (sections, unresolved items, source references) with
 * subdued provenance metadata.
 */
const store = useWorkspaceStore()

const canGenerate = computed<boolean>(() => {
  const activeRoute = store.activeRoute
  return !store.generatingSpec && !store.routeCommandPending
    && activeRoute !== null
    && activeRoute.tipNodeId !== null
})

const selectedSpec = computed<SpecSnapshotResponse | null>(() => {
  if (store.selectedSpec) {
    return store.selectedSpec
  }
  // Presentation default: newest-first, without promoting it to canonical
  // project state.
  return [...store.selectedSpecs].sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0] ?? null
})

// Reload the selected route's snapshot list whenever the selection changes.
watch(
  () => store.selectedRouteId,
  (routeId) => {
    if (routeId) {
      void store.loadRouteSpecs(routeId)
    }
  },
  { immediate: true },
)

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString()
}

function truncated(value: string | null): string {
  return value ?? '—'
}
</script>

<template>
  <div>
    <div class="spec-generate-row">
      <button
        class="btn btn-primary"
        type="button"
        data-test="generate-spec"
        :disabled="!canGenerate"
        @click="store.generateSpec()"
      >
        {{ store.generatingSpec ? 'Generating spec…' : 'Generate spec for active route' }}
      </button>
      <span v-if="store.generatingSpec" class="muted" style="font-size: 12px">
        Generating spec…
      </span>
    </div>
    <p v-if="!store.activeRoute" class="muted info-line" data-test="generate-spec-hint">
      No active route — generate is disabled.
    </p>
    <p
      v-else-if="store.activeRoute.tipNodeId === null && !store.generatingSpec"
      class="muted info-line"
      data-test="generate-spec-hint"
    >
      The active route has no tip node yet — draft a question before generating.
    </p>

    <div class="meta-text" style="margin-top: 8px">
      Snapshots for route:
      {{ store.selectedRouteId ? store.selectedRouteId.slice(0, 8) : '—' }}
    </div>

    <SpecSnapshotList
      :snapshots="store.selectedSpecs"
      :selected-id="store.selectedSpecId"
      @select="store.selectSpec($event)"
    />

    <div v-if="selectedSpec" class="snapshot-detail" data-test="spec-snapshot-detail">
      <div class="detail-head">
        <h3 style="margin: 0 0 4px">Derived Spec Snapshot</h3>
        <span class="badge badge-open" data-test="derived-label">derived — not source of truth</span>
        <span class="meta-text">created {{ formatTime(selectedSpec.createdAt) }}</span>
      </div>

      <div class="provenance" data-test="spec-provenance">
        <div class="meta-text">snapshot: {{ selectedSpec.id }}</div>
        <div class="meta-text">route: {{ selectedSpec.routeId }}</div>
        <div class="meta-text">tip node: {{ truncated(selectedSpec.tipNodeId) }}</div>
        <div class="meta-text">format: {{ selectedSpec.format }}</div>
        <div class="meta-text">createdByRunId: {{ truncated(selectedSpec.createdByRunId) }}</div>
        <div class="meta-text">contextSnapshotId: {{ truncated(selectedSpec.contextSnapshotId) }}</div>
      </div>

      <section v-for="section in selectedSpec.sections" :key="section.id" class="spec-section" data-test="spec-section">
        <h4 style="margin: 0 0 4px">{{ section.title }}</h4>
        <p style="margin: 0; white-space: pre-wrap">{{ section.content }}</p>
      </section>

      <section v-if="selectedSpec.unresolvedItems.length > 0" class="spec-section">
        <h4 style="margin: 0 0 4px">Unresolved items</h4>
        <ul style="margin: 0; padding-left: 18px">
          <li v-for="(item, index) in selectedSpec.unresolvedItems" :key="index" data-test="unresolved-item">
            {{ item.text }}
          </li>
        </ul>
      </section>

      <section v-if="selectedSpec.sourceRefs.length > 0" class="spec-section">
        <h4 style="margin: 0 0 4px">Source references</h4>
        <ul style="margin: 0; padding-left: 18px">
          <li v-for="(ref, index) in selectedSpec.sourceRefs" :key="index" data-test="source-reference">
            {{ ref.kind }}:{{ ref.refId }}
          </li>
        </ul>
      </section>
    </div>

    <p v-else-if="store.selectedSpecs.length > 0" class="muted info-line">
      Select a snapshot to inspect it.
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