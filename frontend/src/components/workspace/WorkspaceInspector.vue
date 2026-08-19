<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import NodeInspector from '@/components/workspace/NodeInspector.vue'
import RequirementStatePanel from '@/components/RequirementStatePanel.vue'
import SpecSnapshotPanel from '@/components/SpecSnapshotPanel.vue'
import type { SpecAgentGraphNodeData } from '@/graph/graphProjection'
import { useGraphUiStore } from '@/stores/graphUiStore'
import { useWorkspaceStore } from '@/stores/workspaceStore'

/**
 * 右侧检查器：详情 / 需求状态 / 规格。
 *
 * 无节点选中时默认显示需求状态；选中节点后默认显示详情。需求状态与规格
 * 历史始终跟随显式 Focus；生成规格始终针对后端 Active 路线，不改变 Focus。
 */
interface SelectedEdge {
  id: string
  kind: 'lineage' | 'replacement'
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

type InspectorTab = 'details' | 'requirement' | 'spec'
const activeTab = ref<InspectorTab>('requirement')

const readingRouteId = computed<string | null>(() => graphUi.readingRouteId())
const readingRouteLabel = computed(() => {
  if (!readingRouteId.value) return '未选择'
  return workspace.graphView?.routes.find((route) => route.id === readingRouteId.value)?.label?.trim() || '当前路线'
})
const routeLabels = computed<Record<string, string>>(() => Object.fromEntries(
  (workspace.graphView?.routes ?? []).map((route) => [route.id, route.label?.trim() || '路线']),
))

const requirementState = computed(() =>
  readingRouteId.value ? workspace.requirementStatesByRoute[readingRouteId.value] ?? null : null,
)

const snapshots = computed(() =>
  readingRouteId.value ? workspace.specsByRoute[readingRouteId.value] ?? [] : [],
)

const selectedSpecId = computed(() =>
  readingRouteId.value ? workspace.selectedSpecIdByRoute[readingRouteId.value] ?? null : null,
)

// 读取路线变化时，路线级需求状态与规格历史总是从后端加载。
watch(
  readingRouteId,
  (routeId) => {
    if (routeId) {
      void workspace.ensureRequirementState(routeId)
      void workspace.loadRouteSpecs(routeId)
    }
  },
  { immediate: true },
)

// 每次 canonical 刷新完成后，重新从后端加载读取路线的需求状态与规格历史
// （缓存只对单次读取生命周期有效）。
watch(
  () => workspace.refreshing,
  (refreshing, wasRefreshing) => {
    if (!refreshing && wasRefreshing && readingRouteId.value) {
      void workspace.ensureRequirementState(readingRouteId.value)
      void workspace.loadRouteSpecs(readingRouteId.value)
    }
  },
)

// 批准语义：选中节点 → 默认详情；清除选择 → 回到默认需求状态（绝不允许
// 空详情状态落到规格页）。
watch(
  () => props.nodeData,
  (data) => {
    if (data) {
      activeTab.value = 'details'
    } else if (activeTab.value === 'details') {
      activeTab.value = 'requirement'
    }
  },
  { immediate: true },
)

async function handleGenerateSpec(): Promise<void> {
  const result = await workspace.generateSpec()
  void result
}

function selectSpec(snapshotId: string): void {
  if (readingRouteId.value) {
    workspace.selectSpecForRoute(readingRouteId.value, snapshotId)
  }
}
</script>

<template>
  <div class="workspace-inspector panel" data-test="workspace-inspector">
    <div class="inspector-tabs" data-test="inspector-tabs">
      <button
        class="inspector-tab"
        :class="{ active: activeTab === 'details' }"
        data-test="tab-details"
        @click="activeTab = 'details'"
      >
        详情
      </button>
      <button
        class="inspector-tab"
        :class="{ active: activeTab === 'requirement' }"
        data-test="tab-requirement"
        @click="activeTab = 'requirement'"
      >
        需求状态
      </button>
      <button
        class="inspector-tab"
        :class="{ active: activeTab === 'spec' }"
        data-test="tab-spec"
        @click="activeTab = 'spec'"
      >
        规格
      </button>
    </div>

    <div class="inspector-reading-context" data-test="current-reading-route">
      当前查看路线：<strong>{{ readingRouteLabel }}</strong>
    </div>

    <div class="inspector-body">
      <NodeInspector
        v-if="activeTab === 'details' && nodeData && !selectedEdge"
        :data="nodeData"
        @fork="emit('fork', $event)"
        @reanswer="emit('reanswer', $event)"
        @regenerate="emit('regenerate', $event)"
      />

      <div v-else-if="selectedEdge" class="edge-inspector" data-test="edge-inspector">
        <h3 class="node-inspector__title">{{ selectedEdge.kind === 'replacement' ? '替代关系' : '共享路线边' }}</h3>
        <p class="meta-text">该物理边不会自动猜测或切换聚焦路线。</p>
        <p class="meta-text">边：{{ selectedEdge.id }}</p>
        <h4 class="node-inspector__heading">路线成员</h4>
        <ul class="node-inspector__options">
          <li v-for="routeId in selectedEdge.routeIds" :key="routeId" class="node-inspector__option">
            {{ workspace.graphView?.routes.find((route) => route.id === routeId)?.label || '路线' }}
          </li>
          <li v-if="selectedEdge.routeIds.length === 0" class="muted">暂无路线成员。</li>
        </ul>
      </div>

      <RequirementStatePanel
        v-else-if="activeTab === 'requirement'"
        :requirement-state="requirementState"
        :route-id="readingRouteId"
        :loading="workspace.loadingRequirementRouteId === readingRouteId"
      />

      <SpecSnapshotPanel
        v-else
        :route-id="readingRouteId"
        :active-route-id="workspace.activeRoute?.id ?? null"
        :route-labels="routeLabels"
        :snapshots="snapshots"
        :selected-spec-id="selectedSpecId"
        :generating="workspace.generatingSpec"
        :command-pending="workspace.routeCommandPending"
        @generate-spec="handleGenerateSpec"
        @select="selectSpec"
      />
    </div>
  </div>
</template>

<style scoped>
.workspace-inspector {
  height: 100%;
}

.inspector-tabs {
  display: flex;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-subdued);
}

.inspector-tab {
  flex: 1;
  padding: 8px 6px;
  border: 0;
  border-right: 1px solid var(--color-border);
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 13px;
}

.inspector-tab.active {
  background: var(--color-surface);
  color: var(--color-accent);
  font-weight: 600;
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
