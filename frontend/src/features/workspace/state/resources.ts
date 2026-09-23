/**
 * Resource & authoring domain: user-authored draft/resource nodes and the
 * graph commands that shape them (floating creation, connection, disconnection,
 * in-place revision, knowledge confirmation, explicit semantic relations).
 *
 * Every function is the verbatim action body lifted out of `workspaceStore.ts`
 * with `this` replaced by the store instance passed in as the first argument.
 * The store keeps the action names and delegates, so no caller changes.
 *
 * Every async response is validated against the project session captured at
 * start (see `captureProjectSession`): a slow command must never write its
 * feedback or error into a different project era.
 */
import {
  appendContinuation,
  connectFloatingNode as connectFloatingNodeCommand,
  createFloatingDraftNode,
  createRelation,
  disconnectNode as disconnectNodeCommand,
  reviseDraftNode,
  setKnowledgeStatus,
} from '@/features/workspace/api/graphCommands'
import { toDisplayError } from '@/shared/http/displayError'
import { startRouteFromNode } from '@/features/workspace/api/routes'
import type { ResourceSlice } from './slices'
import { captureProjectSession } from './shared'

/**
 * Adds a user-authored idea as a standalone (floating) draft — zero
 * model calls, never connected to any node. The user connects it
 * manually on the canvas. No Active Route is required: the creation
 * context route id is optional (null context is legal). Returns the
 * created node id, or null on failure.
 */
export async function createIdeaAction(store: ResourceSlice): Promise<string | null> {
  if (!store.projectId || store.graphCommandPending) return null
  const activeRouteId = store.activeState?.activeRoute?.id ?? null
  const { projectId, isCurrent } = captureProjectSession(store)
  store.graphCommandPending = true
  store.error = null
  try {
    const created = await createFloatingDraftNode(projectId, activeRouteId, {
      subtype: 'IDEA',
      content: {},
    })
    if (!isCurrent()) return null
    store.feedback = '已创建想法，双击卡片直接编辑'
    await store.refreshWorkspace()
    if (!isCurrent()) return null
    await store.refreshUndoRedoAvailability()
    return created.id
  } catch (err) {
    if (!isCurrent()) return null
    store.error = toDisplayError(err)
    return null
  } finally {
    // Only the owning session releases the lock: a stale command's cleanup
    // must not release the NEW session's graph-command lock (beginProject
    // has already reset it on switch).
    if (isCurrent()) {
      store.graphCommandPending = false
    }
  }
}

/**
 * Continues from a node on an explicit route. The backend appends at the
 * tip or creates an explicit branch from a historical node — the UI never
 * pretends history was rewritten.
 */
export async function continueFromNodeAction(
  store: ResourceSlice,
  nodeId: string,
  routeId: string,
): Promise<boolean> {
  if (!store.projectId || store.graphCommandPending) return false
  const { projectId, isCurrent } = captureProjectSession(store)
  store.graphCommandPending = true
  store.error = null
  try {
    const created = await appendContinuation(projectId, nodeId, routeId, {
      subtype: 'NOTE',
      content: {},
    })
    if (!isCurrent()) return false
    store.feedback = created.branched ? '已从该节点创建探索分支' : '已在当前路线继续'
    await store.refreshWorkspace()
    if (!isCurrent()) return false
    await store.refreshUndoRedoAvailability()
    return true
  } catch (err) {
    if (!isCurrent()) return false
    store.error = toDisplayError(err)
    return false
  } finally {
    // Only the owning session releases the lock: a stale command's cleanup
    // must not release the NEW session's graph-command lock (beginProject
    // has already reset it on switch).
    if (isCurrent()) {
      store.graphCommandPending = false
    }
  }
}

/**
 * "继续生成问题"（想法/知识/资源节点的统一入口）：
 * - 浮动节点：以它为起点创建一条**新独立路线**（节点成为新路线的根+tip），
 *   随即在该路线起草下一个问题。
 * - 已接入某条路线：在该节点开**新分支**（fork + 自动起草），原路线不动。
 *
 * 接入本身不再提供按钮：浮动节点只能由用户把连线拖到路线末端来接入
 * （见 connectFloatingNode / 画布 connect-floating 事件）。
 */
export async function draftQuestionFromNodeAction(
  store: ResourceSlice,
  nodeId: string,
  readingRouteId?: string | null,
): Promise<boolean> {
  if (!store.projectId) return false
  const view = store.graphView
  if (!view) return false
  const membershipRouteIds = view.routes
    .filter((route) => (route.lineageNodeIds ?? []).includes(nodeId))
    .map((route) => route.id)
  if (membershipRouteIds.length === 0) {
    // 浮动节点：开新独立路线并起草（新路线已是 Active 路线）。
    const { projectId, isCurrent } = captureProjectSession(store)
    store.graphCommandPending = true
    store.error = null
    try {
      const result = await startRouteFromNode(projectId, nodeId, null)
      if (!isCurrent()) return false
      store.feedback = `已创建「${result.route.label ?? '新路线'}」，正在起草第一个问题…`
      await store.refreshWorkspace()
      if (!isCurrent()) return false
      store.graphCommandPending = false
      const drafted = await store.draftQuestion()
      if (!isCurrent()) return false
      if (drafted) {
        store.setFocusAfterMutation({
          routeId: result.route.id,
          nodeId: store.activeState?.activeRoute?.tipNodeId ?? null,
        })
      }
      return drafted
    } catch (err) {
      if (!isCurrent()) return false
      store.error = toDisplayError(err)
      return false
    } finally {
      store.graphCommandPending = false
    }
  }
  // 已接入：显式路线开新分支（forkNode 内部自带起草与失败重试）。
  const routeId = readingRouteId
    ?? (membershipRouteIds.length === 1 ? membershipRouteIds[0] : null)
  if (!routeId) {
    store.error = {
      code: 'SOURCE_ROUTE_REQUIRED',
      message: '共享节点请先在上方选择查看路线，再继续生成问题',
    }
    return false
  }
  return store.forkNode(nodeId, routeId)
}

/**
 * 添加资源 = 先创建一个**独立（浮动）资源节点**，不属于任何路线。
 *
 * 这是"资源独立、由用户自己连线"的落地方式：内容先落盘（零模型调用、
 * 不依赖 Active 路线），路线归属是之后在画布上把连线拖到路线末端 tip 时
 * 才发生的独立动作（见 connectFloatingNode）。因此这里不再需要 Active 路线。
 */
export async function createFloatingResourceAction(
  store: ResourceSlice,
  subtype: 'TEXT' | 'URL' | 'FILE' | 'IMAGE' | 'REPOSITORY' | 'API_DOCUMENTATION',
  content: Record<string, unknown>,
): Promise<boolean> {
  if (!store.projectId || store.graphCommandPending) return false
  const { projectId, isCurrent } = captureProjectSession(store)
  store.graphCommandPending = true
  store.error = null
  try {
    await createFloatingDraftNode(projectId, store.activeRoute?.id ?? null, {
      subtype,
      content,
      nodeKind: 'RESOURCE',
    })
    if (!isCurrent()) return false
    store.feedback = '已添加独立资源节点，连线到路线末端即可接入'
    await store.refreshWorkspace()
    if (!isCurrent()) return false
    await store.refreshUndoRedoAvailability()
    return true
  } catch (err) {
    if (!isCurrent()) return false
    store.error = toDisplayError(err)
    return false
  } finally {
    // Only the owning session releases the lock: a stale command's cleanup
    // must not release the NEW session's graph-command lock (beginProject
    // has already reset it on switch).
    if (isCurrent()) {
      store.graphCommandPending = false
    }
  }
}

/**
 * 把浮动节点接入一条路线（成为该路线的新末端）。后端只接受"当前 tip"
 * 作为父节点，且不接受未答题的父节点 —— 手画的一条线不会改写历史。
 */
export async function connectFloatingNodeAction(
  store: ResourceSlice,
  nodeId: string,
  routeId: string,
  parentNodeId: string | null,
): Promise<boolean> {
  if (!store.projectId || store.graphCommandPending) return false
  const { projectId, isCurrent } = captureProjectSession(store)
  store.graphCommandPending = true
  store.error = null
  try {
    await connectFloatingNodeCommand(projectId, nodeId, routeId, parentNodeId)
    if (!isCurrent()) return false
    store.feedback = '已接入路线'
    await store.refreshWorkspace()
    if (!isCurrent()) return false
    await store.refreshUndoRedoAvailability()
    return true
  } catch (err) {
    if (!isCurrent()) return false
    store.error = toDisplayError(err)
    return false
  } finally {
    // Only the owning session releases the lock: a stale command's cleanup
    // must not release the NEW session's graph-command lock (beginProject
    // has already reset it on switch).
    if (isCurrent()) {
      store.graphCommandPending = false
    }
  }
}

/** 把路线末端节点断开为浮动节点（内容保留，只解除归属）。 */
export async function disconnectNodeAction(
  store: ResourceSlice,
  nodeId: string,
  routeId: string,
): Promise<boolean> {
  if (!store.projectId || store.graphCommandPending) return false
  const { projectId, isCurrent } = captureProjectSession(store)
  store.graphCommandPending = true
  store.error = null
  try {
    await disconnectNodeCommand(projectId, nodeId, routeId)
    if (!isCurrent()) return false
    store.feedback = '已断开该节点，它现在是独立节点'
    await store.refreshWorkspace()
    if (!isCurrent()) return false
    await store.refreshUndoRedoAvailability()
    return true
  } catch (err) {
    if (!isCurrent()) return false
    store.error = toDisplayError(err)
    return false
  } finally {
    // Only the owning session releases the lock: a stale command's cleanup
    // must not release the NEW session's graph-command lock (beginProject
    // has already reset it on switch).
    if (isCurrent()) {
      store.graphCommandPending = false
    }
  }
}

/**
 * Saves an in-place edit of a still-editable user draft. `skillId` is the
 * optional "/" picker binding: it rides in the open content map as
 * metadata next to the visible text, and null clears an existing binding.
 */
export async function reviseDraftAction(
  store: ResourceSlice,
  nodeId: string,
  subtype: string,
  text: string,
  skillId: string | null = null,
): Promise<boolean> {
  if (!store.projectId || store.graphCommandPending) return false
  const { projectId, isCurrent } = captureProjectSession(store)
  store.graphCommandPending = true
  store.error = null
  try {
    const content: Record<string, unknown> = text.trim() ? { text: text.trim() } : {}
    if (skillId) {
      content.skillId = skillId
    }
    await reviseDraftNode(projectId, nodeId, {
      subtype,
      content,
    })
    if (!isCurrent()) return false
    store.feedback = '草稿已保存'
    await store.refreshWorkspace()
    if (!isCurrent()) return false
    await store.refreshUndoRedoAvailability()
    return true
  } catch (err) {
    if (!isCurrent()) return false
    store.error = toDisplayError(err)
    return false
  } finally {
    // Only the owning session releases the lock: a stale command's cleanup
    // must not release the NEW session's graph-command lock (beginProject
    // has already reset it on switch).
    if (isCurrent()) {
      store.graphCommandPending = false
    }
  }
}

/** Confirms claim-like knowledge content (PROPOSED -> CONFIRMED). */
export async function confirmKnowledgeAction(
  store: ResourceSlice,
  nodeId: string,
): Promise<boolean> {
  if (!store.projectId || store.graphCommandPending) return false
  const { projectId, isCurrent } = captureProjectSession(store)
  store.graphCommandPending = true
  store.error = null
  try {
    await setKnowledgeStatus(projectId, nodeId, 'CONFIRMED')
    if (!isCurrent()) return false
    store.feedback = '已确认该内容'
    await store.refreshWorkspace()
    if (!isCurrent()) return false
    await store.refreshUndoRedoAvailability()
    return true
  } catch (err) {
    if (!isCurrent()) return false
    store.error = toDisplayError(err)
    return false
  } finally {
    // Only the owning session releases the lock: a stale command's cleanup
    // must not release the NEW session's graph-command lock (beginProject
    // has already reset it on switch).
    if (isCurrent()) {
      store.graphCommandPending = false
    }
  }
}

/** Creates an explicit user semantic relation through the Runtime command. */
export async function createSemanticRelationAction(
  store: ResourceSlice,
  sourceNodeId: string,
  targetNodeId: string,
  relationType: 'RELATED_TO' | 'DEPENDS_ON' | 'DERIVED_FROM' | 'CONFLICTS_WITH' | 'SUPPORTS',
): Promise<boolean> {
  if (!store.projectId || store.graphCommandPending || sourceNodeId === targetNodeId) return false
  const { projectId, isCurrent } = captureProjectSession(store)
  store.graphCommandPending = true
  store.error = null
  try {
    await createRelation(projectId, sourceNodeId, targetNodeId, relationType)
    if (!isCurrent()) return false
    store.feedback = '已添加语义关系'
    await store.refreshWorkspace()
    if (!isCurrent()) return false
    await store.refreshUndoRedoAvailability()
    return true
  } catch (err) {
    if (!isCurrent()) return false
    store.error = toDisplayError(err)
    return false
  } finally {
    // Only the owning session releases the lock: a stale command's cleanup
    // must not release the NEW session's graph-command lock (beginProject
    // has already reset it on switch).
    if (isCurrent()) {
      store.graphCommandPending = false
    }
  }
}
