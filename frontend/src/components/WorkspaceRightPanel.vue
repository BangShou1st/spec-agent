<script setup lang="ts">
import { ref, watch } from 'vue'
import type { RequirementStateView } from '@/api/types'
import RequirementStatePanel from './RequirementStatePanel.vue'
import SpecSnapshotPanel from './SpecSnapshotPanel.vue'

/**
 * Right side of the workspace: tabbed panels. "Requirement State" preserves
 * the Phase 7.1 backend-derived panel; "Spec Snapshots" shows the selected
 * route's derived snapshot history plus generation for the active route.
 */
const props = defineProps<{
  requirementState: RequirementStateView | null
}>()

type Tab = 'requirement' | 'spec'

const tab = ref<Tab>('requirement')

watch(
  () => props.requirementState?.routeId,
  (routeId, previous) => {
    if (previous !== undefined && previous !== null && routeId === null) {
      // The active route was cleared (archive/delete): the requirement-state
      // tab now shows the backend's safe empty read model; stay on it so the
      // user sees the honest empty state.
      tab.value = 'requirement'
    }
  },
)
</script>

<template>
  <div class="panel">
    <div class="tab-bar" data-test="right-tabs">
      <button
        type="button"
        class="tab-button"
        :class="{ active: tab === 'requirement' }"
        data-test="tab-requirement"
        @click="tab = 'requirement'"
      >
        Requirement State
      </button>
      <button
        type="button"
        class="tab-button"
        :class="{ active: tab === 'spec' }"
        data-test="tab-spec"
        @click="tab = 'spec'"
      >
        Spec Snapshots
      </button>
    </div>
    <div class="panel-body">
      <RequirementStatePanel v-if="tab === 'requirement'" :requirement-state="requirementState" />
      <SpecSnapshotPanel v-else />
    </div>
  </div>
</template>

<style scoped>
.tab-bar {
  display: flex;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-subdued);
}

.tab-button {
  flex: 1;
  padding: 8px 10px;
  border: 0;
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 13px;
  font-weight: 500;
  border-bottom: 2px solid transparent;
}

.tab-button.active {
  color: var(--color-accent);
  border-bottom-color: var(--color-accent);
  background: var(--color-surface);
}
</style>