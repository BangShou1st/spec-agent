<script setup lang="ts">
import { computed } from 'vue'
import type { RouteLifecycleStatus, RouteLineageNodeView, RouteResponse } from '@/api/types'

/**
 * Historical node inspector. Shows an immutable historical clarification node
 * (question, purpose, options, option impacts, provenance) without editing it.
 * Re-answering history is only possible through fork/regenerate semantics;
 * answers are never submitted to a historical node here.
 */
const props = defineProps<{
  node: RouteLineageNodeView | null
  route: RouteResponse | null
  isTip: boolean
  commandPending: boolean
  pendingRouteCommand: string | null
  isActiveRoute: boolean
}>()

const emit = defineEmits<{
  backToActive: []
  fork: []
  regenerate: []
}>()

const canFork = computed<boolean>(() => {
  if (!props.node || !props.route) {
    return false
  }
  // The runtime can only fork from a node covered by an OPEN route; for
  // non-OPEN routes the user restores the route first.
  return props.route.lifecycleStatus === 'open'
})

const forkDisabledReason = computed<string | null>(() => {
  if (!props.node || !props.route) {
    return null
  }
  if (props.route.lifecycleStatus !== 'open') {
    return 'Restore this route first to fork from its nodes.'
  }
  return null
})

const canRegenerate = computed<boolean>(() => {
  if (!props.node || !props.route) {
    return false
  }
  // Root regeneration is unsupported and regenerate stays scoped to the
  // active OPEN route so several OPEN routes sharing ancestors never surprise
  // the user with an unintended source route.
  if (props.node.parentNodeId === null) {
    return false
  }
  return props.route.isActive && props.route.lifecycleStatus === 'open'
})

const regenerateDisabledReason = computed<string | null>(() => {
  if (!props.node || !props.route) {
    return null
  }
  if (props.node.parentNodeId === null) {
    return 'Root question regeneration is not supported.'
  }
  if (!props.route.isActive) {
    return props.route.lifecycleStatus === 'open'
      ? 'Activate this route first to regenerate.'
      : 'Restore this route first to regenerate.'
  }
  return null
})

const lifecycleHint = (status: RouteLifecycleStatus): string => {
  const map: Record<RouteLifecycleStatus, string> = {
    open: 'Open',
    superseded: 'Superseded',
    archived: 'Archived',
    deleted: 'Deleted',
  }
  return map[status] ?? status
}

const pendingAll = (): boolean => props.commandPending
</script>

<template>
  <div class="panel">
    <div class="panel-header">
      Historical Node
      <span v-if="route" class="meta-text"> on {{ route.label ?? 'route' }}</span>
    </div>
    <div class="panel-body">
      <div v-if="node === null" class="empty-state">
        <p style="margin-top: 0"><strong>No historical node selected</strong></p>
        <p class="muted">Select a node in the route lineage to inspect it.</p>
      </div>

      <div v-else>
        <h2 class="question" data-test="historical-question">{{ node.question }}</h2>

        <div class="meta-text" style="margin-bottom: 10px">
          <span class="badge badge-superseded" data-test="historical-tip" v-if="isTip">current tip</span>
          <span
            v-if="node.supersedesNodeId"
            class="badge badge-superseded"
            data-test="historical-supersedes"
          >
            supersedes another node
          </span>
          <span v-if="node.parentNodeId === null" class="badge badge-open">root node</span>
        </div>

        <p v-if="node.purpose" class="purpose" data-test="historical-purpose">{{ node.purpose }}</p>

        <fieldset v-if="node.options.length > 0" style="border: 0; padding: 0; margin: 0 0 12px">
          <legend class="secondary" style="font-size: 13px">Options on this node</legend>
          <div
            v-for="option in node.options"
            :key="option.id"
            class="option-item-static"
            data-test="historical-option"
          >
            <span class="option-label">{{ option.label }}</span>
            <span v-if="option.impact" class="option-impact"> — {{ option.impact }}</span>
          </div>
        </fieldset>

        <div class="provenance" data-test="historical-provenance">
          <div class="meta-text">node id: {{ node.id }}</div>
          <div v-if="node.parentNodeId" class="meta-text">parent: {{ node.parentNodeId }}</div>
          <div v-if="node.supersedesNodeId" class="meta-text">supersedes: {{ node.supersedesNodeId }}</div>
          <div class="meta-text">created: {{ new Date(node.createdAt).toLocaleString() }}</div>
          <div v-if="route" class="meta-text">route: {{ route.id }} ({{ lifecycleHint(route.lifecycleStatus) }})</div>
        </div>

        <div class="history-actions" style="margin-top: 12px">
          <button
            class="btn btn-primary"
            type="button"
            data-test="fork-from-here"
            :disabled="pendingAll() || !canFork"
            @click="emit('fork')"
          >
            Fork from here
          </button>
          <button
            class="btn"
            type="button"
            data-test="regenerate-this-question"
            :disabled="pendingAll() || !canRegenerate"
            @click="emit('regenerate')"
          >
            Regenerate this question
          </button>
          <button
            class="btn"
            type="button"
            data-test="back-to-active"
            :disabled="pendingAll()"
            @click="emit('backToActive')"
          >
            Back to active question
          </button>
        </div>

        <p v-if="forkDisabledReason" class="muted info-line" data-test="fork-disabled-reason">
          {{ forkDisabledReason }}
        </p>
        <p v-if="regenerateDisabledReason" class="muted info-line" data-test="regenerate-disabled-reason">
          {{ regenerateDisabledReason }}
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.option-item-static {
  padding: 8px 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  margin-bottom: 6px;
}

.provenance {
  border-top: 1px solid var(--color-border);
  padding-top: 8px;
}

.history-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.info-line {
  margin: 8px 0 0;
  font-size: 12px;
}
</style>