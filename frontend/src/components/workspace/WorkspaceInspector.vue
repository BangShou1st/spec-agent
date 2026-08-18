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
 * 历史始终跟随 readingRouteId（focus ?? active）；生成规格始终针对后端
 * 当前路线，成功后清除 Focus 让读取路线跟随返回的产物。
 */
const props = defineProps<{ nodeData: SpecAgentGraphNodeData | null }>()

const emit = defineEmits<{
  fork: [nodeId: string]
  regenerate: [nodeId: string]
}>()

const workspace = useWorkspaceStore()
const graphUi = useGraphUiStore()

type InspectorTab = 'details' | 'requirement' | 'spec'
const activeTab = ref<InspectorTab>('requirement')

const readingRouteId = computed<string | null>(() =>
  graphUi.readingRouteId(workspace.activeRoute?.id ?? null),
)

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

watch(
  () => props.nodeData,
  (data) => {
    if (data) {
      activeTab.value = 'details'
    }
  },
  { immediate: true },
)

async function handleGenerateSpec(): Promise<void> {
  const result = await workspace.generateSpec()
  if (result) {
    // 生成结果属于后端返回的路线：清除 Focus，让读取路线跟随该产物。
    graphUi.clearFocusRoute()
  }
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

    <div class="inspector-body">
      <NodeInspector
        v-if="activeTab === 'details' && nodeData"
        :data="nodeData"
        @fork="emit('fork', $event)"
        @regenerate="emit('regenerate', $event)"
      />

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
</style>