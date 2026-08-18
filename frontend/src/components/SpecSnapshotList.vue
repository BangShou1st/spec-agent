<script setup lang="ts">
import type { SpecSnapshotResponse } from '@/api/types'

/**
 * Snapshot history list for one route. Snapshots are historical derived
 * artifacts: older snapshots are never removed from the browser when a new one
 * is generated, and the newest is never treated as canonical project state.
 * Presentation order is newest-first (sorted client-side by createdAt).
 */
defineProps<{
  snapshots: SpecSnapshotResponse[]
  selectedId: string | null
}>()

const emit = defineEmits<{
  select: [snapshotId: string]
}>()

function sorted(snapshots: SpecSnapshotResponse[]): SpecSnapshotResponse[] {
  return [...snapshots].sort((a, b) => b.createdAt.localeCompare(a.createdAt))
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString()
}
</script>

<template>
  <div>
    <p v-if="snapshots.length === 0" class="muted" data-test="specs-empty">
      No spec snapshots for this route yet.
    </p>
    <ul v-else class="snapshot-list" data-test="spec-snapshot-list">
      <li
        v-for="snapshot in sorted(snapshots)"
        :key="snapshot.id"
        class="snapshot-item"
        :class="{ selected: snapshot.id === selectedId }"
        data-test="spec-snapshot-item"
        @click="emit('select', snapshot.id)"
      >
        <div class="snapshot-row">
          <span class="badge badge-open">Derived Spec Snapshot</span>
          <span class="meta-text">{{ formatTime(snapshot.createdAt) }}</span>
        </div>
        <div class="meta-text">
          {{ snapshot.sections.length }} section(s) · {{ snapshot.sourceRefs.length }} source ref(s)
        </div>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.snapshot-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.snapshot-item {
  padding: 8px 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  margin-bottom: 6px;
  cursor: pointer;
}

.snapshot-item.selected {
  border-color: var(--color-accent);
  background: var(--color-accent-soft);
}

.snapshot-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 4px;
}
</style>