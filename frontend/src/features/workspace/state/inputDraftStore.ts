// 文件名:inputDraftStore.ts
// 用途:输入草稿 store(Pinia):按 项目+节点+路线/阅读上下文 键持久化用户未提交输入(选项+自由文本),防止拖画布/切焦点/重挂载丢输入;提交成功只清除自己的草稿。
/*
 * InputDraftStore:按 节点 + 路线/阅读上下文 键持久化用户输入
 * (选中的选项 + 自由文本)。防止用户拖动画布、切换焦点、提交或组件重挂载
 * 时丢输入。会话存储还能在本标签页内跨页面刷新存活。草稿始终只是浏览器
 * 侧输入,绝不是 canonical 回答;提交成功只从内存与存储中清除它自己的
 * 那条身份。
 *
 * 键格式:`${projectId}:${nodeId}:${routeId ?? ''}:${readContext ?? ''}`
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
    // 存储不可用或 JSON 非法绝不能妨碍输入。
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
      // 配额/隐私模式失败时保留内存中的实时输入。
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
