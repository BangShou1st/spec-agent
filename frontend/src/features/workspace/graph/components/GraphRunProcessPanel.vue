<script lang="ts">
// 文件名:GraphRunProcessPanel.vue
// 用途:进行中 AgentRun 的节点内过程面板(可复用):渲染阶段文案、最新汇总与后端白名单的步骤时间线;不读 store、不解释 run 语义,节点卡与 Inspector 都可嵌入。
/*
 * 进行中 AgentRun 的节点内过程面板(可复用)。
 *
 * 纯展示:渲染阶段文案、最新汇总以及后端允许展示的步骤时间线。它不读
 * 任何 store,也绝不解释 run 语义,因此节点卡片和 Inspector 都可以嵌入
 * 它而不与 run 注册表耦合。
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
  /** run 仍在执行时为 false;失败的 run 不显示转圈。 */
  running?: boolean
  /** 紧凑模式为小卡片渲染更少的步骤(尾部)。 */
  compact?: boolean
}>(), {
  phase: null,
  summary: null,
  steps: () => [],
  running: true,
  compact: false,
})

/** 时间线方向:最旧在上,最新在下。 */
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
