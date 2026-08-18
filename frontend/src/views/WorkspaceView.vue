<script setup lang="ts">
import { onMounted } from 'vue'
import ApiErrorBanner from '@/components/ApiErrorBanner.vue'
import ClarificationPanel from '@/components/ClarificationPanel.vue'
import RequirementStatePanel from '@/components/RequirementStatePanel.vue'
import RouteListPanel from '@/components/RouteListPanel.vue'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import type { SubmitAnswerRequest } from '@/api/types'

const props = defineProps<{ projectId: string }>()

const store = useWorkspaceStore()

onMounted(() => {
  void store.loadWorkspace(props.projectId)
})

async function retry(): Promise<void> {
  await store.loadWorkspace(props.projectId)
}

async function handleDraft(): Promise<void> {
  await store.draftQuestion()
}

async function handleAnswer(payload: SubmitAnswerRequest): Promise<void> {
  await store.submitAnswer(payload)
}
</script>

<template>
  <div>
    <h1 style="margin-top: 0">{{ store.project?.title ?? 'Workspace' }}</h1>

    <ApiErrorBanner
      v-if="store.error"
      :message="store.error.message"
      :code="store.error.code"
      retry-label="Retry"
      :retrying="store.loading"
      @retry="retry"
    />

    <p v-if="store.loading" class="muted">Loading workspace…</p>

    <div v-else class="workspace-layout">
      <RouteListPanel :project-title="store.project?.title ?? null" :routes="store.routes" />
      <ClarificationPanel
        :active-route="store.activeState?.activeRoute ?? null"
        :active-node="store.activeState?.activeNode ?? null"
        :drafting="store.drafting"
        :submitting="store.submitting"
        :feedback="store.feedback"
        @draft="handleDraft"
        @answer="handleAnswer"
      />
      <RequirementStatePanel :requirement-state="store.requirementState" />
    </div>

    <p v-if="store.refreshing" class="muted" data-test="refreshing">Refreshing workspace…</p>
  </div>
</template>