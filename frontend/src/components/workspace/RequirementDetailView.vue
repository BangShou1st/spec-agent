<script setup lang="ts">
import { computed, ref } from 'vue'
import type { RequirementClaimView, RequirementStateView } from '@/api/types'

/**
 * 完整需求二级视图：分组原样使用后端四个数组，不做任何提升/重解释。
 * 默认只展示可读 claim 文本；sourceNodeId / sourceAnswerId / raw kind /
 * confidence / route UUID 藏在"技术详情"里。
 */
const props = defineProps<{
  requirementState: RequirementStateView | null
  routeLabel: string | null
  routeId: string | null
  loading: boolean
}>()

const emit = defineEmits<{
  back: []
}>()

const techOpen = ref(false)

const groups = computed(() => {
  if (!props.requirementState) return []
  return [
    { key: 'confirmed', title: '已确认', claims: props.requirementState.confirmed },
    { key: 'unresolved', title: '未解决', claims: props.requirementState.unresolved },
    { key: 'assumed', title: '假定', claims: props.requirementState.assumed },
    { key: 'rejected', title: '已拒绝', claims: props.requirementState.rejected },
  ] as const
})

function confidenceText(claim: RequirementClaimView): string {
  if (claim.confidence === null || claim.confidence === undefined) return '—'
  return `${Math.round(claim.confidence * 100)}%`
}
</script>

<template>
  <div class="requirement-detail" data-test="requirement-detail">
    <button
      class="btn btn-small requirement-detail__back"
      type="button"
      data-test="requirement-back"
      @click="emit('back')"
    >
      返回项目状态
    </button>
    <h3 class="requirement-detail__title">完整需求状态</h3>
    <p class="requirement-detail__route">
      当前查看 <strong>{{ routeLabel ?? '未选择路线' }}</strong>
    </p>
    <p v-if="loading" class="muted">正在加载需求状态…</p>
    <p v-else-if="!requirementState" class="muted">暂无需求状态。</p>
    <template v-else>
      <section
        v-for="group in groups"
        :key="group.key"
        class="requirement-detail__group"
        :data-test="`claim-group-${group.key}`"
      >
        <h4>{{ group.title }}（{{ group.claims.length }}）</h4>
        <ul class="requirement-detail__list">
          <li
            v-for="(claim, index) in group.claims"
            :key="`${group.key}-${index}-${claim.text}`"
            class="requirement-detail__claim"
          >
            <p class="requirement-detail__text">{{ claim.text }}</p>
          </li>
        </ul>
        <p v-if="group.claims.length === 0" class="muted">无。</p>
      </section>
      <div class="requirement-detail__tech">
        <button
          class="btn btn-small"
          type="button"
          data-test="claim-tech-toggle"
          :aria-expanded="techOpen ? 'true' : 'false'"
          @click="techOpen = !techOpen"
        >
          {{ techOpen ? '收起技术详情' : '技术详情' }}
        </button>
        <div v-if="techOpen" data-test="claim-tech">
          <template v-for="group in groups" :key="group.key">
            <div
              v-for="(claim, index) in group.claims"
              :key="`tech-${group.key}-${index}`"
              class="meta-text"
            >
              {{ claim.kind }} · 置信度 {{ confidenceText(claim) }} ·
              节点 {{ claim.sourceNodeId ?? '—' }} · 回答 {{ claim.sourceAnswerId ?? '—' }}
            </div>
          </template>
          <p class="meta-text">路线标识：{{ routeId ?? '—' }}</p>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.requirement-detail__title {
  margin: 8px 0 4px;
  font-size: 14px;
}

.requirement-detail__route {
  font-size: 12px;
  color: var(--color-text-secondary);
}

.requirement-detail__group > h4 {
  margin: 12px 0 6px;
  font-size: 13px;
}

.requirement-detail__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.requirement-detail__claim {
  padding: 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
}

.requirement-detail__text {
  margin: 0;
  font-size: 13px;
}

.requirement-detail__tech {
  margin-top: 12px;
  border-top: 1px solid var(--color-border);
  padding-top: 8px;
}

.requirement-detail__tech > summary {
  cursor: pointer;
  font-size: 13px;
  color: var(--color-text-secondary);
}
</style>
