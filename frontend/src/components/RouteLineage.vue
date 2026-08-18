<script setup lang="ts">
import type { RouteLineageNodeView } from '@/api/types'

/**
 * Renders one route's historical node chain (root→tip order) as a simple
 * indented list. No graph/canvas library. Nodes are immutable; clicking one
 * only selects it for inspection.
 */
defineProps<{
  nodes: RouteLineageNodeView[]
  selectedNodeId: string | null
}>()

const emit = defineEmits<{
  selectNode: [nodeId: string]
}>()
</script>

<template>
  <ul class="lineage-list" data-test="route-lineage">
    <li v-if="nodes.length === 0" class="muted" data-test="lineage-empty">
      No nodes in this route yet.
    </li>
    <li
      v-for="(node, index) in nodes"
      :key="node.id"
      class="lineage-node"
      :class="{ selected: node.id === selectedNodeId }"
      :style="{ paddingLeft: `${12 + index * 14}px` }"
      data-test="lineage-node"
      @click="emit('selectNode', node.id)"
    >
      <div class="lineage-node-question">{{ node.question }}</div>
      <div class="meta-text">
        <span v-if="index === nodes.length - 1" class="badge badge-superseded" data-test="tip-node">
          tip
        </span>
        <span
          v-if="node.supersedesNodeId"
          class="badge badge-superseded"
          data-test="supersedes-node"
        >
          supersedes
        </span>
        <span v-if="node.parentNodeId === null" class="badge badge-open" data-test="root-node">
          root
        </span>
      </div>
    </li>
  </ul>
</template>

<style scoped>
.lineage-list {
  list-style: none;
  margin: 4px 0 0;
  padding: 0;
}

.lineage-node {
  padding: 6px 8px;
  border-radius: var(--radius);
  border: 1px solid transparent;
  cursor: pointer;
}

.lineage-node:hover {
  background: var(--color-subdued);
}

.lineage-node.selected {
  border-color: var(--color-accent);
  background: var(--color-accent-soft);
}

.lineage-node-question {
  font-size: 13px;
  line-height: 1.35;
}
</style>