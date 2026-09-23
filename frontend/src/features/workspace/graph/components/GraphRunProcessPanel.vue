<script lang="ts">
/**
 * Reusable in-node process panel for an in-flight AgentRun.
 *
 * Presentation-only: it renders the phase copy, the latest composed summary
 * and the step timeline that the backend whitelisted for display. It reads
 * nothing from stores and never interprets run semantics, so the node card
 * and the Inspector can embed it without coupling to the run registry.
 */
export default { name: 'GraphRunProcessPanel' }
</script>

<script setup lang="ts">
import { computed } from 'vue'
import type { RunProgressStep } from '@/features/workspace/api/agentRuns'
import { phaseToCopy } from '@/features/workspace/graph/phaseCopy'

const props = withDefaults(defineProps<{
  phase?: string | null
  summary?: string | null
  steps?: RunProgressStep[]
  /** False while the run is still executing; a failed run shows no spinner. */
  running?: boolean
  /** Compact mode renders fewer steps (the tail) for small cards. */
  compact?: boolean
}>(), {
  phase: null,
  summary: null,
  steps: () => [],
  running: true,
  compact: false,
})

/** Timeline direction: oldest at the top, newest at the bottom. */
const visibleSteps = computed(() => {
  const steps = props.steps.filter((step) => step.summary != null)
  return props.compact && steps.length > 3 ? steps.slice(-3) : steps
})

const latestStepSequence = computed(() => {
  const steps = visibleSteps.value
  return steps.length ? steps[steps.length - 1].sequence : null
})

function stepLabel(step: RunProgressStep): string {
  return step.summary ?? phaseToCopy(step.phase)
}
</script>

<template>
  <div
    class="run-process-panel"
    :class="{ 'run-process-panel--failed': running === false }"
    data-test="run-process-panel"
  >
    <p class="run-process-panel__phase">
      <span v-if="running" class="run-process-panel__spinner" aria-hidden="true"></span>
      {{ phaseToCopy(phase) }}
    </p>

    <p v-if="summary" class="run-process-panel__summary">{{ summary }}</p>

    <ol v-if="visibleSteps.length" class="run-process-panel__steps" data-test="run-process-steps">
      <li
        v-for="step in visibleSteps"
        :key="step.sequence"
        class="run-process-panel__step"
        :class="{ 'run-process-panel__step--latest': step.sequence === latestStepSequence }"
      >
        <span class="run-process-panel__step-label">{{ stepLabel(step) }}</span>
        <ul v-if="step.items && step.items.length" class="run-process-panel__items">
          <li v-for="(item, index) in step.items" :key="index">{{ item }}</li>
        </ul>
      </li>
    </ol>
  </div>
</template>
