<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import NodeInspector from '@/components/workspace/NodeInspector.vue'
import ProjectSummary from '@/components/workspace/ProjectSummary.vue'
import RequirementDetailView from '@/components/workspace/RequirementDetailView.vue'
import type { SpecAgentGraphNodeData } from '@/graph/graphProjection'
import { useGraphUiStore } from '@/stores/graphUiStore'
import { useWorkspaceStore } from '@/stores/workspaceStore'

/**
 * 上下文检查器：单一表面，按选择切换。
 *
 * - 选中节点 → NodeInspector
 * - 选中边 → 边上下文视图
 * - 无选择 → 项目摘要（二级：完整需求视图 ↔ 返回）
 *
 * 不再有顶层 详情 / 需求状态 / 规格 tabs；规格已搬到 Graph 中央 Spec Dock。
 * 选择变化会重置二级视图，但绝不改变 Focus / Active 路线。
 */
interface SelectedEdge {
  id: string
  kind: 'lineage' | 'replacement' | 'relation'
  relationType?: string | null
  routeIds: string[]
}

const props = defineProps<{
  nodeData: SpecAgentGraphNodeData | null
  selectedEdge?: SelectedEdge | null
}>()

const emit = defineEmits<{
  fork: [nodeId: string]
  reanswer: [nodeId: string]
  regenerate: [nodeId: string]
}>()

const workspace = useWorkspaceStore()
const graphUi = useGraphUiStore()

type SecondaryView = 'summary' | 'requirements'
const secondaryView = ref<SecondaryView>('summary')
const inspectorBody = ref<HTMLElement | null>(null)

/**
 * 二级视图切换后保持键盘焦点：把焦点移到新视图的第一个可操作按钮，
 * 避免焦点因旧按钮卸载而丢失到 body。
 */
async function switchSecondaryView(view: SecondaryView): Promise<void> {
  secondaryView.value = view
  await nextTick()
  const target = inspectorBody.value?.querySelector<HTMLElement>(
    view === 'requirements'
      ? '[data-test="requirement-back"]'
      : '[data-test="open-requirements"]',
  )
  target?.focus()
}

const readingRouteId = computed<string | null>(() => {
  const focusedRouteId = graphUi.readingRouteId()
  if (focusedRouteId) return focusedRouteId
  const routes = workspace.graphView?.routes ?? []
  return routes.length === 1 ? routes[0].id : null
})
const readingRouteLabel = computed(() => {
  if (!readingRouteId.value) return '未选择'
  return workspace.graphView?.routes.find((route) => route.id === readingRouteId.value)?.label?.trim() || '当前路线'
})

const requirementState = computed(() =>
  readingRouteId.value ? workspace.requirementStatesByRoute[readingRouteId.value] ?? null : null,
)
const requirementLoading = computed(() =>
  workspace.loadingRequirementRouteId === readingRouteId.value,
)

// 读取路线变化时，路线级需求状态总是从后端加载。
watch(
  readingRouteId,
  (routeId) => {
    if (routeId) {
      void workspace.ensureRequirementState(routeId)
    }
  },
  { immediate: true },
)

// 每次 canonical 刷新完成后，重新从后端加载读取路线的需求状态。
watch(
  () => workspace.refreshing,
  (refreshing, wasRefreshing) => {
    if (!refreshing && wasRefreshing && readingRouteId.value) {
      void workspace.ensureRequirementState(readingRouteId.value)
    }
  },
)

// 选择节点/边 → 回到主视图；选择变化不触碰 Focus / Active。
watch(
  [() => props.nodeData, () => props.selectedEdge],
  () => {
    secondaryView.value = 'summary'
  },
)

function edgeRouteLabel(routeId: string): string {
  return workspace.graphView?.routes.find((route) => route.id === routeId)?.label?.trim() || '路线'
}
</script>

<template>
  <div class="workspace-inspector panel" data-test="workspace-inspector">
    <div class="inspector-reading-context" data-test="current-reading-route">
      当前查看路线：<strong>{{ readingRouteLabel }}</strong>
    </div>

    <div ref="inspectorBody" class="inspector-body">
      <NodeInspector
        v-if="nodeData && !selectedEdge"
        :data="nodeData"
        @fork="emit('fork', $event)"
        @reanswer="emit('reanswer', $event)"
        @regenerate="emit('regenerate', $event)"
      />

      <div v-else-if="selectedEdge" class="edge-inspector" data-test="edge-inspector">
        <h3 class="node-inspector__title">
          {{ selectedEdge.kind === 'replacement' ? '替代关系'
            : selectedEdge.kind === 'relation' ? '语义关系' : '共享路线边' }}
        </h3>
        <p v-if="selectedEdge.kind === 'relation'" class="meta-text">
          手动创建的节点连接（可在图上拖线新增，撤销可移除）。
        </p>
        <p v-else class="meta-text">该物理边不会自动猜测或切换聚焦路线。</p>
        <template v-if="selectedEdge.kind !== 'relation'">
          <h4 class="node-inspector__heading">路线成员</h4>
          <ul class="node-inspector__options">
            <li v-for="routeId in selectedEdge.routeIds" :key="routeId" class="node-inspector__option">
              {{ edgeRouteLabel(routeId) }}
            </li>
            <li v-if="selectedEdge.routeIds.length === 0" class="muted">暂无路线成员。</li>
          </ul>
        </template>
        <details class="node-inspector__secondary">
          <summary>更多详情</summary>
          <div class="node-inspector__secondary-body">
            <p class="meta-text">边：{{ selectedEdge.id }}</p>
            <p v-if="selectedEdge.kind === 'relation'" class="meta-text">
              类型：{{ selectedEdge.relationType ?? 'RELATED_TO' }}
            </p>
          </div>
        </details>
      </div>

      <RequirementDetailView
        v-else-if="secondaryView === 'requirements'"
        :requirement-state="requirementState"
        :route-label="readingRouteLabel"
        :route-id="readingRouteId"
        :loading="requirementLoading"
        @back="switchSecondaryView('summary')"
      />

      <ProjectSummary
        v-else
        :requirement-state="requirementState"
        :route-label="readingRouteLabel"
        :route-id="readingRouteId"
        :loading="requirementLoading"
        @open-requirements="switchSecondaryView('requirements')"
      />
    </div>
  </div>
</template>

<style scoped>
.workspace-inspector {
  height: 100%;
}

.inspector-body {
  padding: 12px;
  overflow-y: auto;
  flex: 1;
  min-height: 0;
}

.inspector-reading-context {
  padding: 8px 12px;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-subdued);
  font-size: 12px;
}
</style>
