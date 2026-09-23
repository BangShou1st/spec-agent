/**
 * InputDraftStore: persists user input (selected option + free text) per
 * node + route/read context key. This prevents input loss when the user
 * drags the canvas, switches focus, submits, or the component remounts.
 * Session storage also survives page reloads in this tab. Drafts remain
 * browser-only input, never canonical Answers; successful submission clears
 * only its own identity from both memory and storage.
 *
 * Key format: `${projectId}:${nodeId}:${routeId ?? ''}:${readContext ?? ''}`
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export interface InputDraft {
  selectedOptionId: string | null
  /** 多选题的全量选择（用户顺序）；单选题为 null。 */
  selectedOptionIds?: string[] | null
  freeText: string
}

export const INPUT_DRAFT_STORAGE_KEY = 'spec-agent:input-drafts:v1'

function isInputDraft(value: unknown): value is InputDraft {
  if (typeof value !== 'object' || value === null) return false
  const draft = value as Record<string, unknown>
  return typeof draft.freeText === 'string'
    && (draft.selectedOptionId === null || typeof draft.selectedOptionId === 'string')
    && (draft.selectedOptionIds == null || (Array.isArray(draft.selectedOptionIds)
      && draft.selectedOptionIds.every((id) => typeof id === 'string')))
}

function restoreDrafts(): Map<string, InputDraft> {
  const restored = new Map<string, InputDraft>()
  try {
    const raw: unknown = JSON.parse(sessionStorage.getItem(INPUT_DRAFT_STORAGE_KEY) ?? 'null')
    if (!Array.isArray(raw)) return restored
    for (const entry of raw) {
      if (Array.isArray(entry) && entry.length === 2
        && typeof entry[0] === 'string' && isInputDraft(entry[1])) {
        restored.set(entry[0], entry[1])
      }
    }
  } catch {
    // Unavailable storage or invalid JSON must not prevent typing.
  }
  return restored
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
  const drafts = ref<Map<string, InputDraft>>(restoreDrafts())

  function persistDrafts(): void {
    try {
      if (drafts.value.size === 0) sessionStorage.removeItem(INPUT_DRAFT_STORAGE_KEY)
      else sessionStorage.setItem(INPUT_DRAFT_STORAGE_KEY, JSON.stringify([...drafts.value]))
    } catch {
      // Quota/private-mode failures preserve the live in-memory input.
    }
  }

  function setDraft(
    projectId: string,
    nodeId: string,
    draft: InputDraft,
    routeId?: string | null,
    readContext?: string | null,
  ) {
    drafts.value.set(draftKey(projectId, nodeId, routeId, readContext), {
      ...draft,
      ...(draft.selectedOptionIds ? { selectedOptionIds: [...draft.selectedOptionIds] } : {}),
    })
    persistDrafts()
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
    persistDrafts()
  }

  const draftCount = computed(() => drafts.value.size)

  return { drafts, setDraft, getDraft, clearDraft, draftCount }
})
