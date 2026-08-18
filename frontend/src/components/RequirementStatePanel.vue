<script setup lang="ts">
import { computed } from 'vue'
import type { RequirementClaimView, RequirementStateView } from '@/api/types'

/**
 * 需求状态面板（只读）。渲染后端派生的分组（已确认 / 假定 / 未解决 /
 * 已拒绝）。前端从不把假定/未解决提升为已确认，内容保持原样。
 */
const props = defineProps<{
  requirementState: RequirementStateView | null
  routeId: string | null
  loading: boolean
}>()

const groups = computed(() => {
  if (props.requirementState === null) {
    return []
  }
  return [
    { key: 'confirmed', title: '已确认', claims: props.requirementState.confirmed },
    { key: 'assumed', title: '假定', claims: props.requirementState.assumed },
    { key: 'unresolved', title: '未解决', claims: props.requirementState.unresolved },
    { key: 'rejected', title: '已拒绝', claims: props.requirementState.rejected },
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
  <div class="panel requirement-state-panel" data-test="requirement-state-panel">
    <div class="panel-header">需求状态</div>
    <div class="panel-body">
      <p v-if="loading" class="muted">正在加载需求状态…</p>
      <template v-else-if="requirementState === null">
        <p class="muted" data-test="requirement-empty">暂无需求状态。</p>
      </template>

      <template v-else>
        <p class="meta-text" style="margin-top: 0">路线：{{ routeId ?? '—' }}</p>

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
          <p v-if="group.claims.length === 0" class="muted" style="margin: 0">无。</p>
        </section>
      </template>
    </div>
  </div>
</template>
