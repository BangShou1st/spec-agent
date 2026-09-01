<script setup lang="ts">
import { computed } from 'vue'
import { BaseEdge, getBezierPath, type EdgeProps } from '@vue-flow/core'
import type { SpecAgentGraphEdgeData } from '@/graph/graphProjection'

const props = defineProps<EdgeProps<SpecAgentGraphEdgeData>>()

const pathResult = computed(() =>
  getBezierPath({
    sourceX: props.sourceX,
    sourceY: props.sourceY,
    sourcePosition: props.sourcePosition,
    targetX: props.targetX,
    targetY: props.targetY,
    targetPosition: props.targetPosition,
    // A restrained curvature keeps the edge directional without large loops.
    curvature: 0.18,
  }),
)

const edgeClasses = computed(() => [
  'adaptive-graph-edge__path',
  `graph-edge--${props.data.kind}`,
  `graph-edge--${props.data.visualWeight}`,
])

const edgeStyle = computed(() => ({
  ...(props.data.kind === 'replacement' ? { stroke: 'var(--color-warn)', strokeDasharray: '6 4' } : {}),
  ...(props.data.kind === 'relation' ? { stroke: '#8b5cf6', strokeDasharray: '2 5', strokeWidth: 2 } : {}),
  ...(props.data.visualWeight === 'dimmed' ? { opacity: 0.3 } : {}),
  ...(props.data.visualWeight === 'focus' ? { stroke: 'var(--color-accent)', strokeWidth: 2 } : {}),
}))
</script>

<template>
  <BaseEdge
    :id="id"
    :path="pathResult[0]"
    :label-x="pathResult[1]"
    :label-y="pathResult[2]"
    :marker-start="markerStart"
    :marker-end="markerEnd"
    :interaction-width="interactionWidth"
    :class="edgeClasses"
    :style="edgeStyle"
    data-test="adaptive-graph-edge"
  />
</template>
