<script setup lang="ts">
import type { SpecSnapshotResponse } from '@/api/types'

/**
 * 单个路线的规格快照历史列表。快照是派生的历史产物：新生成不会移除旧
 * 快照，最新快照也不会被当作项目权威状态。按创建时间倒序展示。
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
      该路线还没有规格快照。
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
          <span class="badge badge-open">派生规格快照</span>
          <span class="meta-text">{{ formatTime(snapshot.createdAt) }}</span>
        </div>
        <div class="meta-text">
          {{ snapshot.sections.length }} 个章节 · {{ snapshot.sourceRefs.length }} 个来源引用
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
