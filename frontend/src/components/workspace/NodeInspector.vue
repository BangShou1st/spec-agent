<script setup lang="ts">
import type { SpecAgentGraphNodeData } from '@/graph/graphProjection'

/**
 * 节点详情检查器（右栏）。只读展示节点问题、目的、全部选项、每条路线的
 * 回答/等待状态与路线归属。历史动作只向上发出意图；回答提交永远在 Graph
 * 节点内，这里不提供第二套提交界面。Fork / Regenerate 只在历史节点上提供：
 * 当前待回答节点（canAnswer）保持纯详情，历史动作不作用于等待回答的节点。
 *
 * 按 routeIds（routeStates）展示 route-specific state：同一共享节点上
 * 可以同时出现「路线 A · 已回答」与「路线 B · 等待回答」，身份不会混淆。
 */
defineProps<{ data: SpecAgentGraphNodeData | null }>()

const emit = defineEmits<{
  fork: [nodeId: string]
  regenerate: [nodeId: string]
}>()

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString()
}
</script>

<template>
  <div class="node-inspector" data-test="node-inspector">
    <template v-if="data">
      <h3 class="node-inspector__title" data-test="node-detail-question">{{ data.node.question }}</h3>
      <p v-if="data.node.purpose" class="graph-node-purpose">{{ data.node.purpose }}</p>
      <p class="meta-text">创建于 {{ formatTime(data.node.createdAt) }}</p>

      <h4 class="node-inspector__heading">选项</h4>
      <ul class="node-inspector__options">
        <li v-for="option in data.node.options" :key="option.id" class="node-inspector__option">
          <span class="graph-option-label">{{ option.label }}</span>
          <span v-if="option.impact" class="graph-option-impact">{{ option.impact }}</span>
        </li>
        <li v-if="data.node.options.length === 0" class="muted">无选项。</li>
      </ul>

      <h4 class="node-inspector__heading">路线归属</h4>
      <p class="meta-text">
        {{ data.routeIds.length }} 条路线
        <template v-if="data.isShared">（共享节点）</template>
      </p>

      <h4 class="node-inspector__heading">各路线回答</h4>
      <div v-if="data.routeStates.length > 0" class="node-inspector__answers">
        <div
          v-for="state in data.routeStates"
          :key="state.routeId"
          class="graph-route-answer"
          :class="{ 'graph-route-answer--primary': state.answer?.isPrimary }"
          :data-test="`route-answer-${state.routeId}`"
        >
          <span class="meta-text">路线 {{ state.routeId }}</span>
          <template v-if="state.answer">
            <span v-if="state.answer.selectedOptionLabel" class="badge badge-open">{{ state.answer.selectedOptionLabel }}</span>
            <p v-if="state.answer.freeText" class="graph-answer-text">{{ state.answer.freeText }}</p>
          </template>
          <span v-else class="badge badge-warn" data-test="route-waiting">等待回答</span>
        </div>
      </div>
      <p v-else class="muted" data-test="node-detail-no-answers">该节点所属路线都还没有回答。</p>

      <!-- 历史节点才提供 Fork / Regenerate；当前待回答节点保持只读详情，
           回答只发生在 Graph 节点内部。 -->
      <div v-if="!data.canAnswer" class="node-inspector__actions">
        <button class="btn btn-small" data-test="inspector-fork" @click="emit('fork', data.node.id)">从此分支</button>
        <button
          class="btn btn-small"
          data-test="inspector-regenerate"
          :disabled="data.node.parentNodeId === null"
          @click="emit('regenerate', data.node.id)"
        >
          重新生成这个问题
        </button>
      </div>
    </template>
    <p v-else class="muted" data-test="node-detail-empty">选择一个节点查看详情。</p>
  </div>
</template>

<style scoped>
.node-inspector__title {
  margin: 0 0 6px;
  font-size: 15px;
}

.node-inspector__heading {
  margin: 14px 0 6px;
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--color-text-secondary);
}

.node-inspector__options {
  list-style: none;
  margin: 0;
  padding: 0;
}

.node-inspector__option {
  padding: 6px 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  margin-bottom: 4px;
}

.node-inspector__answers {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.node-inspector__actions {
  display: flex;
  gap: 6px;
  margin-top: 14px;
}
</style>
