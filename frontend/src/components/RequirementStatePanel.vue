<script setup lang="ts">
import { computed } from 'vue'
import type { RequirementClaimView, RequirementStateView } from '@/api/types'

/**
 * Right requirement-state panel. Loads only the backend read endpoint and
 * renders the backend-derived groups verbatim: Confirmed, Assumptions,
 * Unresolved, and a visually subdued Rejected section. The frontend never
 * promotes an assumed/unresolved claim into confirmed.
 */
const props = defineProps<{
  requirementState: RequirementStateView | null
}>()

const groups = computed(() => {
  if (props.requirementState === null) {
    return []
  }
  return [
    { key: 'confirmed', title: 'Confirmed', claims: props.requirementState.confirmed },
    { key: 'assumed', title: 'Assumptions', claims: props.requirementState.assumed },
    { key: 'unresolved', title: 'Unresolved', claims: props.requirementState.unresolved },
    { key: 'rejected', title: 'Rejected', claims: props.requirementState.rejected },
  ] as const
})

function formatConfidence(claim: RequirementClaimView): string {
  if (claim.confidence === null || claim.confidence === undefined) {
    return ''
  }
  return `${Math.round(claim.confidence * 100)}%`
}
</script>

<template>
  <div class="panel">
    <div class="panel-header">Requirement State</div>
    <div class="panel-body">
      <template v-if="requirementState === null">
        <p class="muted">Requirement state is not loaded yet.</p>
      </template>

      <template v-else>
        <p class="meta-text" style="margin-top: 0">
          route: {{ requirementState.routeId ?? '—' }}
        </p>

        <section
          v-for="group in groups"
          :key="group.key"
          class="claim-group"
          :data-test="`claim-group-${group.key}`"
        >
          <h3>{{ group.title }}</h3>
          <div
            v-for="claim in group.claims"
            :key="`${group.key}-${claim.text}-${claim.sourceNodeId ?? ''}`"
            class="claim-card"
            :class="{ rejected: group.key === 'rejected' }"
          >
            <p class="claim-text">{{ claim.text }}</p>
            <p class="meta-text" style="margin: 2px 0">
              {{ claim.kind }}
              <template v-if="formatConfidence(claim)"> · {{ formatConfidence(claim) }}</template>
            </p>
            <p class="meta-text" style="margin: 0">
              node {{ claim.sourceNodeId ?? '—' }} · answer {{ claim.sourceAnswerId ?? '—' }}
            </p>
          </div>
          <p v-if="group.claims.length === 0" class="muted" style="margin: 0">
            None.
          </p>
        </section>
      </template>
    </div>
  </div>
</template>
