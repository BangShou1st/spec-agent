<script setup lang="ts">
import { computed } from 'vue'
import type { RequirementStateView } from '@/api/types'

/**
 * 无选择时的轻量项目摘要：只读计数 + 当前查看路线。
 * 不拉取数据、不展示 raw id；完整需求走 `open-requirements` 二级视图。
 */
const props = defineProps<{
  requirementState: RequirementStateView | null
  routeLabel: string | null
  routeId: string | null
  loading: boolean
}>()

const emit = defineEmits<{
  'open-requirements': []
}>()

const counts = computed(() => ({
  confirmed: props.requirementState?.confirmed.length ?? 0,
  unresolved: props.requirementState?.unresolved.length ?? 0,
  assumed: props.requirementState?.assumed.length ?? 0,
  rejected: props.requirementState?.rejected.length ?? 0,
}))
</script>

<template>
  <div class="project-summary" data-test="project-summary">
    <h3 class="project-summary__title">项目状态</h3>
    <p v-if="loading" class="muted">正在加载需求状态…</p>
    <dl v-else class="project-summary__counts">
      <div class="project-summary__row">
        <dt>已确认</dt>
        <dd>{{ counts.confirmed }}</dd>
      </div>
      <div class="project-summary__row">
        <dt>未解决</dt>
        <dd>{{ counts.unresolved }}</dd>
      </div>
      <div class="project-summary__row">
        <dt>假定</dt>
        <dd>{{ counts.assumed }}</dd>
      </div>
      <div class="project-summary__row">
        <dt>已拒绝</dt>
        <dd>{{ counts.rejected }}</dd>
      </div>
    </dl>
    <p class="project-summary__route">
      当前查看 <strong>{{ routeLabel ?? '未选择路线' }}</strong>
    </p>
    <button
      class="btn btn-small project-summary__open"
      type="button"
      data-test="open-requirements"
      @click="emit('open-requirements')"
    >
      查看完整需求状态
    </button>
  </div>
</template>

<style scoped>
.project-summary__title {
  margin: 0 0 8px;
  font-size: 14px;
}

.project-summary__counts {
  margin: 0 0 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.project-summary__row {
  display: flex;
  justify-content: space-between;
  margin: 0;
  font-size: 13px;
}

.project-summary__row dd {
  margin: 0;
  font-weight: 600;
}

.project-summary__route {
  font-size: 12px;
  color: var(--color-text-secondary);
}
</style>
