/**
 * InputDraftStore: persists user input (selected option + free text) per
 * node + route/read context key. This prevents input loss when the user
 * drags the canvas, switches focus, submits, or the component remounts.
 *
 * Key format: `${projectId}:${nodeId}:${routeId ?? ''}:${readContext ?? ''}`
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export interface InputDraft {
  selectedOptionId: string | null
  freeText: string
}

function draftKey(
  projectId: string,
  nodeId: string,
  routeId?: string | null,
  readContext?: string | null,
): string {
  return `${projectId}:${nodeId}:${routeId ?? ''}:${readContext ?? ''}`
}

export const useInputDraftStore = defineStore('inputDraft', () => {
  const drafts = ref<Map<string, InputDraft>>(new Map())

  function setDraft(
    projectId: string,
    nodeId: string,
    draft: InputDraft,
    routeId?: string | null,
    readContext?: string | null,
  ) {
    drafts.value.set(draftKey(projectId, nodeId, routeId, readContext), draft)
  }

  function getDraft(
    projectId: string,
    nodeId: string,
    routeId?: string | null,
    readContext?: string | null,
  ): InputDraft | undefined {
    return drafts.value.get(draftKey(projectId, nodeId, routeId, readContext))
  }

  function clearDraft(
    projectId: string,
    nodeId: string,
    routeId?: string | null,
    readContext?: string | null,
  ) {
    drafts.value.delete(draftKey(projectId, nodeId, routeId, readContext))
  }

  const draftCount = computed(() => drafts.value.size)

  return { drafts, setDraft, getDraft, clearDraft, draftCount }
})
